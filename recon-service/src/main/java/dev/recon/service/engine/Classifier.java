package dev.recon.service.engine;

import dev.recon.service.engine.Records.BankRec;
import dev.recon.service.engine.Records.GatewayRec;
import dev.recon.service.engine.Records.LedgerRec;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Pure classification rules. Given the ledger entry, gateway settlement row and bank credits that
 * were matched for one payment, list every finding and pick the primary one by precedence
 * ({@link Finding#rank()}). No I/O, so every rule is covered by table-driven unit tests.
 */
public final class Classifier {

    /** Lower rank first; on a tie the finding with more money at risk wins, then reason name. */
    static final Comparator<Finding> PRECEDENCE = Comparator.comparingInt(Finding::rank)
            .thenComparing(Comparator.comparingLong(Finding::atRiskMinor).reversed())
            .thenComparing(Finding::reason);

    private Classifier() {
    }

    /**
     * @param ledger       ledger entry or null
     * @param gateway      gateway settlement row or null (at least one of ledger/gateway is non-null)
     * @param bankByKey    bank credits whose UTR equals the gateway settlement_ref (value date <= asOf)
     * @param fallbackBank a bank credit matched by amount + merchant + date window, or null
     * @param asOf         latest date for which bank information is considered complete
     */
    public static Outcome classifyPayment(LedgerRec ledger, GatewayRec gateway, List<BankRec> bankByKey,
                                          BankRec fallbackBank, LocalDate asOf, ReconPolicy policy) {
        if (ledger == null && gateway == null) {
            throw new IllegalArgumentException("nothing to classify");
        }
        List<Finding> findings = new ArrayList<>();
        String paymentId = ledger != null ? ledger.paymentId()
                : gateway.merchantOrderId() != null ? gateway.merchantOrderId() : gateway.gatewayTxnId();
        LocalDate date = gateway != null ? gateway.txnDate() : ledger.businessDate();
        String merchant = gateway != null ? gateway.merchantId() : ledger.merchantId();
        String currency = gateway != null ? gateway.currency() : ledger.currency();
        List<BankRec> credits = new ArrayList<>(bankByKey);
        credits.sort(Comparator.comparing(BankRec::valueDate).thenComparing(BankRec::bankRef));
        BankRec bank = !credits.isEmpty() ? credits.getFirst() : fallbackBank;
        boolean lowConfidence = credits.isEmpty() && fallbackBank != null;

        boolean outOfOrder = ledger != null && ledger.outOfOrderEvents() > 0;
        if (ledger != null && ledger.duplicateDeliveries() > 0) {
            findings.add(new Finding(ResultClass.DUPLICATE, Finding.DUPLICATE_CALLBACK, 0));
        }

        if (gateway == null) {
            findings.add(new Finding(ResultClass.MISSING_IN_GATEWAY, Finding.NOT_IN_SETTLEMENT_FILE, ledger.amountMinor()));
        } else {
            if (ledger == null) {
                findings.add(new Finding(ResultClass.MISSING_IN_LEDGER, Finding.NO_CALLBACK, gateway.grossMinor()));
            } else if (!ledger.succeeded()) {
                findings.add(new Finding(ResultClass.MISSING_IN_LEDGER, Finding.LEDGER_NOT_SUCCESS, gateway.grossMinor()));
            } else {
                amountFinding(ledger.amountMinor(), ledger.currency(), gateway.grossMinor(), gateway.currency(), policy, findings);
            }

            if (credits.size() > 1) {
                long extra = credits.subList(1, credits.size()).stream().mapToLong(BankRec::amountMinor).sum();
                findings.add(new Finding(ResultClass.DUPLICATE, Finding.DUPLICATE_BANK_CREDIT, extra));
            }
            if (bank == null) {
                LocalDate graceEnd = date.plusDays(policy.graceDays());
                findings.add(new Finding(ResultClass.MISSING_IN_BANK,
                        asOf.isAfter(graceEnd) ? Finding.NOT_CREDITED : Finding.AWAITING_CREDIT, gateway.netMinor()));
            } else {
                amountFinding(gateway.netMinor(), gateway.currency(), bank.amountMinor(), bank.currency(), policy, findings);
                long days = ChronoUnit.DAYS.between(date, bank.valueDate());
                if (days > policy.settlementDays()) {
                    findings.add(new Finding(ResultClass.LATE_SETTLEMENT,
                            days <= policy.graceDays() ? Finding.SETTLED_WITHIN_GRACE : Finding.SETTLED_AFTER_GRACE, 0));
                }
            }
        }

        Finding primary = findings.stream().min(PRECEDENCE)
                .orElseGet(() -> new Finding(ResultClass.MATCHED,
                        lowConfidence ? Finding.LOW_CONFIDENCE_FALLBACK
                                : outOfOrder ? Finding.OUT_OF_ORDER_CALLBACK : Finding.CLEAN, 0));
        if (outOfOrder && primary.resultClass() != ResultClass.MATCHED) {
            findings.add(new Finding(ResultClass.MATCHED, Finding.OUT_OF_ORDER_CALLBACK, 0));
        }
        if (lowConfidence && primary.resultClass() != ResultClass.MATCHED) {
            findings.add(new Finding(ResultClass.MATCHED, Finding.LOW_CONFIDENCE_FALLBACK, 0));
        }
        findings.sort(PRECEDENCE);

        List<String> bankRefs = new ArrayList<>();
        if (!credits.isEmpty()) {
            credits.forEach(c -> bankRefs.add(c.bankRef()));
        } else if (fallbackBank != null) {
            bankRefs.add(fallbackBank.bankRef());
        }
        return new Outcome(Outcome.PAYMENT, paymentId, date, merchant, primary.resultClass(), primary.reason(),
                lowConfidence ? Outcome.LOW : Outcome.HIGH, List.copyOf(findings),
                ledger == null ? null : ledger.amountMinor(), gateway == null ? null : gateway.grossMinor(),
                bank == null ? null : bank.amountMinor(), primary.atRiskMinor(), currency, paymentId,
                gateway != null ? gateway.gatewayTxnId() : ledger.gatewayTxnId(),
                gateway == null ? null : gateway.settlementRef(), List.copyOf(bankRefs));
    }

    /** A bank credit that no payment claims. */
    public static Outcome classifyOrphan(BankRec bank, String merchantId) {
        String reason = bank.utr() == null ? Finding.NO_REFERENCE : Finding.UNKNOWN_UTR;
        Finding f = new Finding(ResultClass.ORPHAN_BANK_CREDIT, reason, bank.amountMinor());
        return new Outcome(Outcome.BANK_CREDIT, bank.bankRef(), bank.valueDate(), merchantId,
                ResultClass.ORPHAN_BANK_CREDIT, reason, Outcome.HIGH, List.of(f), null, null, bank.amountMinor(),
                bank.amountMinor(), bank.currency(), null, null, bank.utr(), List.of(bank.bankRef()));
    }

    /** A second settlement row for a payment that already has one. */
    public static Outcome classifyDuplicateGatewayRow(GatewayRec g) {
        Finding f = new Finding(ResultClass.DUPLICATE, Finding.DUPLICATE_SETTLEMENT_ROW, g.grossMinor());
        return new Outcome(Outcome.PAYMENT, g.gatewayTxnId(), g.txnDate(), g.merchantId(), ResultClass.DUPLICATE,
                f.reason(), Outcome.HIGH, List.of(f), null, g.grossMinor(), null, g.grossMinor(), g.currency(),
                g.merchantOrderId(), g.gatewayTxnId(), g.settlementRef(), List.of());
    }

    private static void amountFinding(long expected, String expectedCcy, long actual, String actualCcy,
                                      ReconPolicy policy, List<Finding> out) {
        if (!expectedCcy.equals(actualCcy)) {
            out.add(new Finding(ResultClass.AMOUNT_MISMATCH, Finding.CURRENCY_MISMATCH, Math.max(expected, actual)));
            return;
        }
        long diff = Math.abs(actual - expected);
        if (diff == 0) {
            return;
        }
        out.add(new Finding(ResultClass.AMOUNT_MISMATCH,
                diff <= policy.toleranceMinor() ? Finding.WITHIN_TOLERANCE : Finding.BEYOND_TOLERANCE, diff,
                actual - expected));
    }
}
