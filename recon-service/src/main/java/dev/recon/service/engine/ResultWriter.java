package dev.recon.service.engine;

import dev.recon.service.engine.ReconRepository.Partition;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.sql.Date;
import java.util.ArrayList;
import java.util.List;

/** Replaces the stored outcomes of one partition (delete + batch insert, same transaction). */
@Component
public class ResultWriter {

    private final JdbcTemplate jdbc;

    public ResultWriter(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void replace(Partition p, List<Outcome> outcomes, long runId) {
        jdbc.update("DELETE FROM recon_result WHERE business_date = ? AND merchant_id = ?",
                Date.valueOf(p.date()), p.merchantId());
        List<Object[]> rows = new ArrayList<>(outcomes.size());
        for (Outcome o : outcomes) {
            String refs = String.join(" ", o.bankRefs());
            rows.add(new Object[]{o.entityType(), o.entityId(), Date.valueOf(o.businessDate()), o.merchantId(),
                    o.resultClass().name(), o.reason(), o.confidence(), o.findingsText(), o.ledgerAmountMinor(),
                    o.gatewayAmountMinor(), o.bankAmountMinor(), o.atRiskMinor(), o.currency(), o.paymentId(),
                    o.gatewayTxnId(), o.utr(), refs.length() > 200 ? refs.substring(0, 200) : refs, runId});
        }
        for (int i = 0; i < rows.size(); i += 5_000) {
            jdbc.batchUpdate("INSERT INTO recon_result (entity_type, entity_id, business_date, merchant_id, result_class, "
                    + "reason, confidence, findings, ledger_amount_minor, gateway_amount_minor, bank_amount_minor, "
                    + "amount_at_risk_minor, currency, payment_id, gateway_txn_id, utr, bank_refs, run_id) "
                    + "VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)", rows.subList(i, Math.min(rows.size(), i + 5_000)));
        }
    }
}
