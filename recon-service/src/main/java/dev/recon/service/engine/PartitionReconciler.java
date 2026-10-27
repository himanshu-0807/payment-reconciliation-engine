package dev.recon.service.engine;

import dev.recon.service.engine.Records.BankRec;
import dev.recon.service.engine.Records.GatewayRec;
import dev.recon.service.engine.Records.LedgerRec;
import dev.recon.service.engine.ReconRepository.Partition;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Matches and classifies one (business date, merchant) partition. The result depends only on the
 * data and asOf, never on which other partitions ran before or concurrently: queries are read-only
 * and the fallback rule is a mutual-uniqueness test over the data.
 */
@Component
public class PartitionReconciler {

    private final ReconRepository repo;

    public PartitionReconciler(ReconRepository repo) {
        this.repo = repo;
    }

    public List<Outcome> reconcile(Partition p, LocalDate asOf, ReconPolicy policy) {
        List<LedgerRec> ledger = repo.ledger(p);
        List<GatewayRec> gateway = new ArrayList<>(repo.gateway(p));
        gateway.sort(Comparator.comparing(GatewayRec::gatewayTxnId));
        Map<String, List<BankRec>> creditsByUtr = new HashMap<>();
        for (BankRec b : repo.bankByUtr(p, asOf)) {
            creditsByUtr.computeIfAbsent(b.utr(), k -> new ArrayList<>()).add(b);
        }
        Map<String, LedgerRec> ledgerById = new HashMap<>();
        Map<String, LedgerRec> ledgerByTxn = new HashMap<>();
        for (LedgerRec l : ledger) {
            ledgerById.put(l.paymentId(), l);
            if (l.gatewayTxnId() != null) {
                ledgerByTxn.put(l.gatewayTxnId(), l);
            }
        }

        List<Outcome> out = new ArrayList<>(gateway.size() + 16);
        Set<String> usedLedger = new HashSet<>();
        Set<String> seenOrders = new HashSet<>();
        for (GatewayRec g : gateway) {
            String orderKey = g.merchantOrderId() != null ? g.merchantOrderId() : g.gatewayTxnId();
            if (!seenOrders.add(orderKey)) {
                out.add(Classifier.classifyDuplicateGatewayRow(g));
                continue;
            }
            // Primary key: payment_id <-> merchant_order_id; secondary: gateway_txn_id.
            LedgerRec l = g.merchantOrderId() != null ? ledgerById.get(g.merchantOrderId()) : null;
            if (l == null) {
                l = ledgerByTxn.get(g.gatewayTxnId());
            }
            if (l != null && !usedLedger.add(l.paymentId())) {
                l = null;
            }
            List<BankRec> credits = g.settlementRef() == null ? List.of()
                    : creditsByUtr.getOrDefault(g.settlementRef(), List.of());
            BankRec fallback = credits.isEmpty() ? repo.uniqueFallback(g, asOf, policy.fallbackWindowDays()) : null;
            out.add(Classifier.classifyPayment(l, g, credits, fallback, asOf, policy));
        }
        for (LedgerRec l : ledger) {
            // A ledger entry that never reached SUCCESS creates no settlement expectation.
            if (!usedLedger.contains(l.paymentId()) && l.succeeded()) {
                out.add(Classifier.classifyPayment(l, null, List.of(), null, asOf, policy));
            }
        }
        for (BankRec b : repo.unreferencedCredits(p, asOf)) {
            if (b.utr() == null && repo.claimedByFallback(b, asOf, policy.fallbackWindowDays())) {
                continue;
            }
            out.add(Classifier.classifyOrphan(b, p.merchantId()));
        }
        out.sort(Comparator.comparing(Outcome::entityType).thenComparing(Outcome::entityId));
        return out;
    }
}
