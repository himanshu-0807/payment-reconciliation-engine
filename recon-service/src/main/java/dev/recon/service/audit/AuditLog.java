package dev.recon.service.audit;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/** Append-only writer for audit_log (UPDATE/DELETE/TRUNCATE are rejected by a DB trigger). */
@Component
public class AuditLog {

    public record Entry(String actor, String action, String entityType, String entityId, String details) {
    }

    public static final String SYSTEM = "system:recon-engine";

    private final JdbcTemplate jdbc;

    public AuditLog(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void record(String actor, String action, String entityType, String entityId, String details) {
        recordAll(List.of(new Entry(actor, action, entityType, entityId, details)));
    }

    public void recordAll(List<Entry> entries) {
        if (entries.isEmpty()) {
            return;
        }
        List<Object[]> rows = new ArrayList<>(entries.size());
        for (Entry e : entries) {
            String d = e.details() == null ? null : e.details().substring(0, Math.min(2000, e.details().length()));
            rows.add(new Object[]{e.actor(), e.action(), e.entityType(), e.entityId(), d});
        }
        jdbc.batchUpdate("INSERT INTO audit_log (actor, action, entity_type, entity_id, details) VALUES (?,?,?,?,?)", rows);
    }
}
