package dev.recon.service.admin;

import dev.recon.service.audit.AuditLog;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Clears ledger, ingested sources, results and the ops queue before a fresh simulation. The audit
 * log is append-only and is deliberately kept; the reset itself is audited.
 */
@Component
public class DataReset {

    private final JdbcTemplate jdbc;
    private final AuditLog audit;

    public DataReset(JdbcTemplate jdbc, AuditLog audit) {
        this.jdbc = jdbc;
        this.audit = audit;
    }

    @Transactional
    public void resetAll(String actor) {
        jdbc.execute("TRUNCATE ledger_entry, processed_event, duplicate_delivery, gateway_txn, bank_txn, ingested_file, "
                + "recon_result, adjustment_journal, exception_comment, recon_exception, recon_run");
        audit.record(actor, "DATA_RESET", "SYSTEM", "all", "ledger, sources, results and ops queue cleared");
    }

    /** Clears results and the ops queue but keeps the ledger and ingested sources (used by benchmarks). */
    @Transactional
    public void resetResults(String actor) {
        jdbc.execute("TRUNCATE recon_result, adjustment_journal, exception_comment, recon_exception, recon_run");
        audit.record(actor, "RESULTS_RESET", "SYSTEM", "results", "results and ops queue cleared; ledger and sources kept");
    }
}
