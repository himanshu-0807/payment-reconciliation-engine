package dev.recon.service.engine;

import dev.recon.service.engine.Records.BankRec;
import dev.recon.service.engine.Records.GatewayRec;
import dev.recon.service.engine.Records.LedgerRec;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.time.LocalDate;
import java.util.List;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.params.provider.Arguments.arguments;

/** Table-driven tests: every exception class, the boundaries of each rule, and the precedence order. */
class ClassifierTest {

    static final LocalDate D = LocalDate.of(2026, 1, 10);
    static final ReconPolicy POLICY = ReconPolicy.defaults(); // tolerance 100, T+2, grace 5, window 7

    static LedgerRec ledger() {
        return new LedgerRec("P1", "G1", "M001", D, 10_000, "INR", "SUCCESS", 0, 0);
    }

    static LedgerRec ledger(String status, int dups, int outOfOrder, long amount) {
        return new LedgerRec("P1", "G1", "M001", D, amount, "INR", status, dups, outOfOrder);
    }

    static GatewayRec gateway() {
        return gateway(10_000);
    }

    static GatewayRec gateway(long gross) {
        long fee = gross * 200 / 10_000;
        return new GatewayRec("G1", "P1", "M001", D, D.plusDays(1), gross, fee, gross - fee, "INR", "U1");
    }

    static BankRec bank(long amount, int days) {
        return bank("B1", amount, days, "INR", "U1");
    }

    static BankRec bank(String ref, long amount, int days, String ccy, String utr) {
        return new BankRec(ref, "SIMACC0000M001", "M001", D.plusDays(days), amount, ccy, utr);
    }

    record Case(LedgerRec l, GatewayRec g, List<BankRec> credits, BankRec fallback, int asOfDays) {
    }

    static Arguments row(String name, Case c, ResultClass cls, String reason, long atRisk) {
        return arguments(name, c, cls, reason, atRisk);
    }

    static Stream<Arguments> cases() {
        LedgerRec l = ledger();
        GatewayRec g = gateway();
        List<BankRec> ok = List.of(bank(9_800, 1));
        return Stream.of(
                // --- single-cause rows: one per class / reason
                row("all three agree", new Case(l, g, ok, null, 10), ResultClass.MATCHED, "CLEAN", 0),
                row("no callback", new Case(null, g, ok, null, 10), ResultClass.MISSING_IN_LEDGER, "NO_CALLBACK", 10_000),
                row("ledger stuck in PENDING", new Case(ledger("PENDING", 0, 0, 10_000), g, ok, null, 10),
                        ResultClass.MISSING_IN_LEDGER, "LEDGER_NOT_SUCCESS", 10_000),
                row("not in settlement file", new Case(l, null, List.of(), null, 10),
                        ResultClass.MISSING_IN_GATEWAY, "NOT_IN_SETTLEMENT_FILE", 10_000),
                row("no credit after grace", new Case(l, g, List.of(), null, 6),
                        ResultClass.MISSING_IN_BANK, "NOT_CREDITED", 9_800),
                row("no credit, grace not over", new Case(l, g, List.of(), null, 3),
                        ResultClass.MISSING_IN_BANK, "AWAITING_CREDIT", 9_800),
                row("no credit, last day of grace", new Case(l, g, List.of(), null, 5),
                        ResultClass.MISSING_IN_BANK, "AWAITING_CREDIT", 9_800),
                row("credited twice", new Case(l, g, List.of(bank(9_800, 1), bank("B2", 9_800, 2, "INR", "U1")), null, 10),
                        ResultClass.DUPLICATE, "DUPLICATE_BANK_CREDIT", 9_800),
                row("callback delivered 3 times", new Case(ledger("SUCCESS", 2, 0, 10_000), g, ok, null, 10),
                        ResultClass.DUPLICATE, "DUPLICATE_CALLBACK", 0),
                row("stale callback after success", new Case(ledger("SUCCESS", 0, 1, 10_000), g, ok, null, 10),
                        ResultClass.MATCHED, "OUT_OF_ORDER_CALLBACK", 0),
                row("bank short by 40 paise", new Case(l, g, List.of(bank(9_760, 1)), null, 10),
                        ResultClass.AMOUNT_MISMATCH, "WITHIN_TOLERANCE", 40),
                row("bank short by exactly tolerance", new Case(l, g, List.of(bank(9_700, 1)), null, 10),
                        ResultClass.AMOUNT_MISMATCH, "WITHIN_TOLERANCE", 100),
                row("bank short by tolerance + 1", new Case(l, g, List.of(bank(9_699, 1)), null, 10),
                        ResultClass.AMOUNT_MISMATCH, "BEYOND_TOLERANCE", 101),
                row("gateway gross differs from ledger", new Case(l, gateway(12_000), List.of(bank(11_760, 1)), null, 10),
                        ResultClass.AMOUNT_MISMATCH, "BEYOND_TOLERANCE", 2_000),
                row("credit in another currency", new Case(l, g, List.of(bank("B1", 9_800, 1, "USD", "U1")), null, 10),
                        ResultClass.AMOUNT_MISMATCH, "CURRENCY_MISMATCH", 9_800),
                row("credit on T+2 is on time", new Case(l, g, List.of(bank(9_800, 2)), null, 10), ResultClass.MATCHED, "CLEAN", 0),
                row("credit on T+3", new Case(l, g, List.of(bank(9_800, 3)), null, 10),
                        ResultClass.LATE_SETTLEMENT, "SETTLED_WITHIN_GRACE", 0),
                row("credit on T+5", new Case(l, g, List.of(bank(9_800, 5)), null, 10),
                        ResultClass.LATE_SETTLEMENT, "SETTLED_WITHIN_GRACE", 0),
                row("credit on T+6", new Case(l, g, List.of(bank(9_800, 6)), null, 10),
                        ResultClass.LATE_SETTLEMENT, "SETTLED_AFTER_GRACE", 0),
                row("matched only by amount+date", new Case(l, g, List.of(), bank("B9", 9_800, 1, "INR", null), 10),
                        ResultClass.MATCHED, "LOW_CONFIDENCE_FALLBACK", 0),
                // --- precedence rows: several problems at once
                row("P1 missing ledger beats duplicate credit",
                        new Case(null, g, List.of(bank(9_800, 1), bank("B2", 9_800, 1, "INR", "U1")), null, 10),
                        ResultClass.MISSING_IN_LEDGER, "NO_CALLBACK", 10_000),
                row("P2 missing gateway beats duplicate callback",
                        new Case(ledger("SUCCESS", 1, 0, 10_000), null, List.of(), null, 10),
                        ResultClass.MISSING_IN_GATEWAY, "NOT_IN_SETTLEMENT_FILE", 10_000),
                row("P3 duplicate credit beats real mismatch",
                        new Case(l, gateway(12_000), List.of(bank(11_760, 1), bank("B2", 11_760, 1, "INR", "U1")), null, 10),
                        ResultClass.DUPLICATE, "DUPLICATE_BANK_CREDIT", 11_760),
                row("P4 real mismatch beats late settlement",
                        new Case(l, g, List.of(bank(9_000, 4)), null, 10), ResultClass.AMOUNT_MISMATCH, "BEYOND_TOLERANCE", 800),
                row("P5 not credited beats fee mismatch on ledger side",
                        new Case(ledger("SUCCESS", 0, 0, 9_950), g, List.of(), null, 10),
                        ResultClass.MISSING_IN_BANK, "NOT_CREDITED", 9_800),
                row("P7 fee mismatch beats late-within-grace (money effect breaks the tie)",
                        new Case(l, g, List.of(bank(9_790, 3)), null, 10), ResultClass.AMOUNT_MISMATCH, "WITHIN_TOLERANCE", 10),
                row("P7 fee mismatch beats duplicate callback",
                        new Case(ledger("SUCCESS", 1, 0, 10_000), g, List.of(bank(9_790, 1)), null, 10),
                        ResultClass.AMOUNT_MISMATCH, "WITHIN_TOLERANCE", 10),
                row("P8 duplicate callback beats out-of-order flag",
                        new Case(ledger("SUCCESS", 1, 1, 10_000), g, ok, null, 10), ResultClass.DUPLICATE, "DUPLICATE_CALLBACK", 0),
                row("fallback match can still be late",
                        new Case(l, g, List.of(), bank("B9", 9_800, 4, "INR", null), 10),
                        ResultClass.LATE_SETTLEMENT, "SETTLED_WITHIN_GRACE", 0));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("cases")
    void classifies(String name, Case c, ResultClass cls, String reason, long atRisk) {
        Outcome o = Classifier.classifyPayment(c.l(), c.g(), c.credits(), c.fallback(), D.plusDays(c.asOfDays()), POLICY);
        assertThat(o.resultClass()).as("class").isEqualTo(cls);
        assertThat(o.reason()).as("reason").isEqualTo(reason);
        assertThat(o.atRiskMinor()).as("at risk").isEqualTo(atRisk);
        assertThat(o.confidence()).isEqualTo(c.credits().isEmpty() && c.fallback() != null ? "LOW" : "HIGH");
    }

    @Test
    void keepsSecondaryFindingsInPrecedenceOrder() {
        Outcome o = Classifier.classifyPayment(ledger("SUCCESS", 1, 1, 10_000), gateway(),
                List.of(bank(9_790, 4)), null, D.plusDays(10), POLICY);
        assertThat(o.findingsText())
                .isEqualTo("AMOUNT_MISMATCH:WITHIN_TOLERANCE;LATE_SETTLEMENT:SETTLED_WITHIN_GRACE;DUPLICATE:DUPLICATE_CALLBACK;MATCHED:OUT_OF_ORDER_CALLBACK");
    }

    @Test
    void feeMismatchCarriesSignedDifferenceForTheAdjustment() {
        Outcome shortCredit = Classifier.classifyPayment(ledger(), gateway(), List.of(bank(9_760, 1)), null, D.plusDays(9), POLICY);
        Outcome overCredit = Classifier.classifyPayment(ledger(), gateway(), List.of(bank(9_830, 1)), null, D.plusDays(9), POLICY);
        assertThat(shortCredit.primary().signedDiffMinor()).isEqualTo(-40);
        assertThat(overCredit.primary().signedDiffMinor()).isEqualTo(30);
    }

    @Test
    void orphanCreditsAreClassifiedByWhetherTheyCarryAReference() {
        assertThat(Classifier.classifyOrphan(bank("B5", 5_000, 1, "INR", "UTRX"), "M001").reason()).isEqualTo("UNKNOWN_UTR");
        Outcome noRef = Classifier.classifyOrphan(bank("B6", 7_000, 1, "INR", null), "M001");
        assertThat(noRef.resultClass()).isEqualTo(ResultClass.ORPHAN_BANK_CREDIT);
        assertThat(noRef.reason()).isEqualTo("NO_REFERENCE");
        assertThat(noRef.atRiskMinor()).isEqualTo(7_000);
    }
}
