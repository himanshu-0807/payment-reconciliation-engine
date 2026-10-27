package dev.recon.service.engine;

import dev.recon.common.Money;
import dev.recon.service.audit.AuditLog;
import dev.recon.service.config.ReconProperties;
import dev.recon.service.engine.ReconRepository.Partition;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.sql.Date;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Brings the ops queue of one partition in line with the latest outcomes. Runs inside the
 * partition's transaction. Re-running with unchanged data only touches last_run_id: no new rows,
 * no status changes, no audit entries, no events. Human decisions (IN_REVIEW, RESOLVED) are never
 * overwritten by the engine, except that a case whose problem disappeared is closed as SUPERSEDED.
 */
@Component
public class ExceptionSynchronizer {

    public record Stats(int created, int autoResolved, int pendingExpected, int escalated, int superseded,
                        int adjustmentsPosted, int adjustmentsPending) {
        public Stats plus(Stats o) {
            return new Stats(created + o.created, autoResolved + o.autoResolved, pendingExpected + o.pendingExpected,
                    escalated + o.escalated, superseded + o.superseded, adjustmentsPosted + o.adjustmentsPosted,
                    adjustmentsPending + o.adjustmentsPending);
        }

        public static Stats zero() {
            return new Stats(0, 0, 0, 0, 0, 0, 0);
        }
    }

    private record Existing(long id, String status, String reason, long atRisk) {
    }

    private record NewRow(Outcome o, AutoFixRules.Decision d) {
    }

    private static final Set<String> HUMAN_OWNED = Set.of("IN_REVIEW", "RESOLVED");
    private static final Set<String> ACTIVE = Set.of("OPEN", "IN_REVIEW", "PENDING_EXPECTED");

    private final JdbcTemplate jdbc;
    private final AuditLog audit;
    private final ReconProperties props;

    public ExceptionSynchronizer(JdbcTemplate jdbc, AuditLog audit, ReconProperties props) {
        this.jdbc = jdbc;
        this.audit = audit;
        this.props = props;
    }

    public Stats sync(Partition p, List<Outcome> outcomes, long runId, List<ExceptionEvent> events) {
        Map<String, Existing> existing = new HashMap<>();
        jdbc.query("SELECT id, entity_type, entity_id, exception_class, status, reason, amount_at_risk_minor "
                        + "FROM recon_exception WHERE business_date = ? AND merchant_id = ?",
                rs -> {
                    existing.put(rs.getString(2) + "|" + rs.getString(3) + "|" + rs.getString(4),
                            new Existing(rs.getLong(1), rs.getString(5), rs.getString(6), rs.getLong(7)));
                }, Date.valueOf(p.date()), p.merchantId());

        List<NewRow> inserts = new ArrayList<>();
        List<Object[]> touches = new ArrayList<>();
        List<Object[]> updates = new ArrayList<>();
        List<AuditLog.Entry> auditEntries = new ArrayList<>();
        int escalated = 0, superseded = 0;

        for (Outcome o : outcomes) {
            if (o.resultClass() == ResultClass.MATCHED) {
                continue;
            }
            AutoFixRules.Decision d = AutoFixRules.decide(o, props.autoFix(), props.approvalThresholdMinor());
            Existing ex = existing.remove(o.entityType() + "|" + o.entityId() + "|" + o.resultClass());
            if (ex == null) {
                inserts.add(new NewRow(o, d));
                continue;
            }
            String next = transition(ex.status(), d.status());
            if (next.equals(ex.status()) && ex.reason().equals(o.reason()) && ex.atRisk() == o.atRiskMinor()) {
                touches.add(new Object[]{runId, ex.id()});
                continue;
            }
            String action = "PENDING_EXPECTED".equals(ex.status()) && "OPEN".equals(next) ? "ESCALATED"
                    : !next.equals(ex.status()) ? "STATUS_" + next : "UPDATED";
            if ("ESCALATED".equals(action)) {
                escalated++;
            }
            updates.add(new Object[]{next, o.reason(), o.atRiskMinor(), runId, d.resolutionReason(), next, ex.id()});
            auditEntries.add(new AuditLog.Entry(AuditLog.SYSTEM, action, "EXCEPTION", Long.toString(ex.id()),
                    "run " + runId + ": " + ex.status() + "/" + ex.reason() + " -> " + next + "/" + o.reason()));
            events.add(event(ex.id(), action, o, next, runId));
        }
        // Whatever is still in `existing` was not reported by this run: the problem is gone.
        for (Map.Entry<String, Existing> e : existing.entrySet()) {
            Existing ex = e.getValue();
            if (ACTIVE.contains(ex.status())) {
                jdbc.update("UPDATE recon_exception SET status = 'AUTO_RESOLVED', resolution_reason = 'SUPERSEDED', "
                        + "resolution_note = ?, resolved_by = ?, resolved_at = now(), updated_at = now(), "
                        + "last_run_id = ?, version = version + 1 WHERE id = ?",
                        "no longer detected by run " + runId, AuditLog.SYSTEM, runId, ex.id());
                auditEntries.add(new AuditLog.Entry(AuditLog.SYSTEM, "SUPERSEDED", "EXCEPTION", Long.toString(ex.id()),
                        "run " + runId + ": " + e.getKey() + " no longer detected"));
                superseded++;
            }
        }
        if (!touches.isEmpty()) {
            jdbc.batchUpdate("UPDATE recon_exception SET last_run_id = ? WHERE id = ?", touches);
        }
        if (!updates.isEmpty()) {
            // Resolution fields follow the new status unless a human owns the case.
            jdbc.batchUpdate("UPDATE recon_exception SET status = ?, reason = ?, amount_at_risk_minor = ?, "
                    + "last_run_id = ?, resolution_reason = CASE WHEN status IN ('IN_REVIEW','RESOLVED') "
                    + "THEN resolution_reason ELSE ? END, resolved_at = CASE WHEN status IN ('IN_REVIEW','RESOLVED') "
                    + "THEN resolved_at WHEN ? = 'AUTO_RESOLVED' THEN now() ELSE NULL END, "
                    + "updated_at = now(), version = version + 1 WHERE id = ?", updates);
        }

        int created = 0, auto = 0, pending = 0, adjPosted = 0, adjPending = 0;
        List<Object[]> adjustments = new ArrayList<>();
        Map<Long, NewRow> byId = insert(inserts, runId);
        for (Map.Entry<Long, NewRow> e : byId.entrySet()) {
            long id = e.getKey();
            Outcome o = e.getValue().o();
            AutoFixRules.Decision d = e.getValue().d();
            created++;
            String detail = o.resultClass() + "/" + o.reason() + " at risk " + Money.format(o.atRiskMinor()) + " "
                    + o.currency() + " (run " + runId + ")";
            auditEntries.add(new AuditLog.Entry(AuditLog.SYSTEM, "CREATED", "EXCEPTION", Long.toString(id), detail));
            if ("AUTO_RESOLVED".equals(d.status())) {
                auto++;
                auditEntries.add(new AuditLog.Entry(AuditLog.SYSTEM, "AUTO_FIX_" + d.resolutionReason(), "EXCEPTION",
                        Long.toString(id), "auto-fix rule applied"));
            } else if ("PENDING_EXPECTED".equals(d.status())) {
                pending++;
                auditEntries.add(new AuditLog.Entry(AuditLog.SYSTEM, "AUTO_FIX_PENDING_EXPECTED", "EXCEPTION",
                        Long.toString(id), "credit not yet due past grace window; re-checked each run"));
            }
            if (d.adjustmentMinor() != null) {
                adjustments.add(new Object[]{id, o.entityId(), d.adjustmentMinor(), o.currency(), d.adjustmentStatus(),
                        AuditLog.SYSTEM});
                if ("POSTED".equals(d.adjustmentStatus())) {
                    adjPosted++;
                } else {
                    adjPending++;
                }
                auditEntries.add(new AuditLog.Entry(AuditLog.SYSTEM, "ADJUSTMENT_" + d.adjustmentStatus(), "EXCEPTION",
                        Long.toString(id), "adjustment " + Money.format(d.adjustmentMinor()) + " " + o.currency()));
            }
            events.add(event(id, "CREATED", o, d.status(), runId));
        }
        if (!adjustments.isEmpty()) {
            jdbc.batchUpdate("INSERT INTO adjustment_journal (exception_id, entity_id, amount_minor, currency, status, "
                    + "created_by) VALUES (?,?,?,?,?,?) ON CONFLICT (exception_id) DO NOTHING", adjustments);
        }
        audit.recordAll(auditEntries);
        return new Stats(created, auto, pending, escalated, superseded, adjPosted, adjPending);
    }

    /** Engine-proposed status vs current status; human-owned states win. */
    static String transition(String current, String proposed) {
        if (HUMAN_OWNED.contains(current)) {
            return current;
        }
        return proposed;
    }

    private Map<Long, NewRow> insert(List<NewRow> rows, long runId) {
        Map<Long, NewRow> out = new LinkedHashMap<>();
        for (int i = 0; i < rows.size(); i += 500) {
            List<NewRow> chunk = rows.subList(i, Math.min(rows.size(), i + 500));
            StringBuilder sql = new StringBuilder("INSERT INTO recon_exception (entity_type, entity_id, business_date, "
                    + "merchant_id, exception_class, reason, amount_at_risk_minor, currency, status, resolution_reason, "
                    + "resolved_by, resolved_at, first_run_id, last_run_id) VALUES ");
            List<Object> args = new ArrayList<>(chunk.size() * 12);
            Map<String, NewRow> byKey = new HashMap<>();
            for (int j = 0; j < chunk.size(); j++) {
                NewRow r = chunk.get(j);
                Outcome o = r.o();
                boolean auto = "AUTO_RESOLVED".equals(r.d().status());
                sql.append(j == 0 ? "" : ",").append("(?,?,?,?,?,?,?,?,?,?,?,").append(auto ? "now()" : "NULL").append(",?,?)");
                Collections.addAll(args, o.entityType(), o.entityId(), Date.valueOf(o.businessDate()), o.merchantId(),
                        o.resultClass().name(), o.reason(), o.atRiskMinor(), o.currency(), r.d().status(),
                        r.d().resolutionReason(), auto ? AuditLog.SYSTEM : null, runId, runId);
                byKey.put(o.entityType() + "|" + o.entityId() + "|" + o.resultClass().name(), r);
            }
            sql.append(" RETURNING id, entity_type, entity_id, exception_class");
            jdbc.query(sql.toString(), rs -> {
                out.put(rs.getLong(1), byKey.get(rs.getString(2) + "|" + rs.getString(3) + "|" + rs.getString(4)));
            }, args.toArray());
        }
        return out;
    }

    private static ExceptionEvent event(long id, String action, Outcome o, String status, long runId) {
        return new ExceptionEvent(id, action, o.entityType(), o.entityId(), o.businessDate(), o.merchantId(),
                o.resultClass().name(), o.reason(), status, o.atRiskMinor(), o.currency(), runId);
    }
}
