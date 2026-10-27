package dev.recon.service.ops;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.sql.Date;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Money at risk = sum of amount_at_risk_minor over unresolved exceptions (OPEN, IN_REVIEW).
 * PENDING_EXPECTED credits are reported separately as "expected in transit": they are not yet due
 * (still inside the grace window), so counting them as at risk would overstate the exposure.
 */
@Service
public class DashboardService {

    public record Bucket(String key, long count, long amountMinor) {
    }

    public record Summary(LocalDate referenceDate, String currency, long moneyAtRiskMinor, long openCount,
                          long expectedInTransitMinor, long pendingExpectedCount, long newAtRiskMinor,
                          List<Bucket> byClass, List<Bucket> aging, List<Bucket> byStatus, List<Bucket> autoFixed,
                          long adjustmentsPendingApproval, Map<String, Object> lastRun) {
    }

    public record TrendPoint(LocalDate businessDate, long moneyAtRiskMinor, long openCount, long expectedInTransitMinor) {
    }

    private static final String UNRESOLVED = "status IN ('OPEN','IN_REVIEW')";

    private final JdbcTemplate jdbc;

    public DashboardService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public Summary summary(LocalDate ref) {
        Date r = Date.valueOf(ref);
        long[] atRisk = sumCount("SELECT count(*), COALESCE(sum(amount_at_risk_minor),0) FROM recon_exception WHERE " + UNRESOLVED);
        long[] pending = sumCount("SELECT count(*), COALESCE(sum(amount_at_risk_minor),0) FROM recon_exception WHERE status = 'PENDING_EXPECTED'");
        long[] fresh = sumCount("SELECT count(*), COALESCE(sum(amount_at_risk_minor),0) FROM recon_exception WHERE "
                + UNRESOLVED + " AND business_date >= ?", Date.valueOf(ref.minusDays(1)));
        List<Bucket> byClass = buckets("SELECT exception_class, count(*), COALESCE(sum(amount_at_risk_minor),0) "
                + "FROM recon_exception WHERE " + UNRESOLVED + " GROUP BY exception_class ORDER BY 3 DESC, 1");
        List<Bucket> agingRaw = buckets("""
                SELECT CASE WHEN ? - business_date <= 1 THEN '0-1d' WHEN ? - business_date <= 3 THEN '2-3d'
                            WHEN ? - business_date <= 7 THEN '4-7d' ELSE '8d+' END AS bucket,
                       count(*), COALESCE(sum(amount_at_risk_minor),0)
                FROM recon_exception WHERE status IN ('OPEN','IN_REVIEW') GROUP BY 1""", r, r, r);
        List<Bucket> aging = new ArrayList<>();
        for (String k : List.of("0-1d", "2-3d", "4-7d", "8d+")) {
            aging.add(agingRaw.stream().filter(b -> b.key().equals(k)).findFirst().orElse(new Bucket(k, 0, 0)));
        }
        List<Bucket> byStatus = buckets("SELECT status, count(*), COALESCE(sum(amount_at_risk_minor),0) "
                + "FROM recon_exception GROUP BY status ORDER BY status");
        List<Bucket> autoFixed = buckets("SELECT resolution_reason, count(*), COALESCE(sum(amount_at_risk_minor),0) "
                + "FROM recon_exception WHERE status = 'AUTO_RESOLVED' GROUP BY resolution_reason ORDER BY 2 DESC");
        Long pendingAdj = jdbc.queryForObject("SELECT count(*) FROM adjustment_journal WHERE status = 'PENDING_APPROVAL'", Long.class);
        List<Map<String, Object>> last = jdbc.queryForList("SELECT id, status, from_date, to_date, as_of_date, parallelism, "
                + "finished_at, duration_ms, partitions, entities, result_digest FROM recon_run ORDER BY id DESC LIMIT 1");
        return new Summary(ref, "INR", atRisk[1], atRisk[0], pending[1], pending[0], fresh[1], byClass, aging, byStatus,
                autoFixed, pendingAdj == null ? 0 : pendingAdj, last.isEmpty() ? null : new LinkedHashMap<>(last.getFirst()));
    }

    public List<TrendPoint> trend(LocalDate ref, int days) {
        if (days < 1 || days > 90) {
            throw new IllegalArgumentException("days must be between 1 and 90");
        }
        return jdbc.query("""
                SELECT d::date AS day,
                       COALESCE(sum(e.amount_at_risk_minor) FILTER (WHERE e.status IN ('OPEN','IN_REVIEW')), 0) AS at_risk,
                       count(e.id) FILTER (WHERE e.status IN ('OPEN','IN_REVIEW')) AS open_count,
                       COALESCE(sum(e.amount_at_risk_minor) FILTER (WHERE e.status = 'PENDING_EXPECTED'), 0) AS in_transit
                FROM generate_series(?::date, ?::date, interval '1 day') d
                LEFT JOIN recon_exception e ON e.business_date = d::date
                GROUP BY d ORDER BY d""",
                (rs, i) -> new TrendPoint(rs.getDate(1).toLocalDate(), rs.getLong(2), rs.getLong(3), rs.getLong(4)),
                Date.valueOf(ref.minusDays(days - 1L)), Date.valueOf(ref));
    }

    private long[] sumCount(String sql, Object... args) {
        return jdbc.query(sql, rs -> {
            rs.next();
            return new long[]{rs.getLong(1), rs.getLong(2)};
        }, args);
    }

    private List<Bucket> buckets(String sql, Object... args) {
        return jdbc.query(sql, (rs, i) -> new Bucket(rs.getString(1), rs.getLong(2), rs.getLong(3)), args);
    }
}
