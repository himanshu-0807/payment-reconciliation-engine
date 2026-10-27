package dev.recon.service.engine;

import dev.recon.service.engine.Records.BankRec;
import dev.recon.service.engine.Records.GatewayRec;
import dev.recon.service.engine.Records.LedgerRec;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.Date;
import java.time.LocalDate;
import java.util.List;

/** Read side of the engine: plain JDBC, every query bounded by one (date, merchant) partition. */
@Repository
public class ReconRepository {

    public record Partition(LocalDate date, String merchantId) {
    }

    public static final String UNMAPPED_MERCHANT = "UNMAPPED";

    private static final RowMapper<LedgerRec> LEDGER = (rs, i) -> new LedgerRec(rs.getString("payment_id"),
            rs.getString("gateway_txn_id"), rs.getString("merchant_id"), rs.getDate("business_date").toLocalDate(),
            rs.getLong("amount_minor"), rs.getString("currency"), rs.getString("status"),
            rs.getInt("duplicate_deliveries"), rs.getInt("out_of_order_events"));

    private static final RowMapper<GatewayRec> GATEWAY = (rs, i) -> new GatewayRec(rs.getString("gateway_txn_id"),
            rs.getString("merchant_order_id"), rs.getString("merchant_id"), rs.getDate("txn_date").toLocalDate(),
            rs.getDate("settlement_date").toLocalDate(), rs.getLong("gross_minor"), rs.getLong("fee_minor"),
            rs.getLong("net_minor"), rs.getString("currency"), rs.getString("settlement_ref"));

    private static final RowMapper<BankRec> BANK = (rs, i) -> new BankRec(rs.getString("bank_ref"),
            rs.getString("account_no"), rs.getString("merchant_id"), rs.getDate("value_date").toLocalDate(),
            rs.getLong("amount_minor"), rs.getString("currency"), rs.getString("utr"));

    private final JdbcTemplate jdbc;

    public ReconRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** Every (date, merchant) with ledger or gateway activity, or bank credits, inside the range. */
    public List<Partition> partitions(LocalDate from, LocalDate to, LocalDate asOf) {
        return jdbc.query("""
                SELECT d, m FROM (
                  SELECT business_date AS d, merchant_id AS m FROM ledger_entry WHERE business_date BETWEEN ? AND ?
                  UNION SELECT txn_date, merchant_id FROM gateway_txn WHERE txn_date BETWEEN ? AND ?
                  UNION SELECT value_date, COALESCE(merchant_id, 'UNMAPPED') FROM bank_txn
                        WHERE value_date BETWEEN ? AND ? AND value_date <= ?
                ) p ORDER BY d, m""", (rs, i) -> new Partition(rs.getDate(1).toLocalDate(), rs.getString(2)),
                d(from), d(to), d(from), d(to), d(from), d(to), d(asOf));
    }

    public LocalDate[] dataRange() {
        return jdbc.query("""
                SELECT min(d), max(d) FROM (
                  SELECT min(business_date) d FROM ledger_entry UNION ALL SELECT max(business_date) FROM ledger_entry
                  UNION ALL SELECT min(txn_date) FROM gateway_txn UNION ALL SELECT max(txn_date) FROM gateway_txn
                  UNION ALL SELECT min(value_date) FROM bank_txn UNION ALL SELECT max(value_date) FROM bank_txn) x""",
                rs -> {
                    rs.next();
                    Date a = rs.getDate(1), b = rs.getDate(2);
                    return a == null ? null : new LocalDate[]{a.toLocalDate(), b.toLocalDate()};
                });
    }

    public int countUnresolvedExceptions(LocalDate from, LocalDate to) {
        Integer n = jdbc.queryForObject("SELECT count(*) FROM recon_exception WHERE status IN ('OPEN','IN_REVIEW') "
                + "AND business_date BETWEEN ? AND ?", Integer.class, d(from), d(to));
        return n == null ? 0 : n;
    }

    /** Latest bank statement received (an empty statement still counts), else the latest credit. */
    public LocalDate latestBankDate() {
        Date d = jdbc.queryForObject("SELECT COALESCE((SELECT max(statement_date) FROM ingested_file WHERE source = 'BANK'), "
                + "(SELECT max(value_date) FROM bank_txn))", Date.class);
        return d == null ? null : d.toLocalDate();
    }

    public List<LedgerRec> ledger(Partition p) {
        return jdbc.query("SELECT * FROM ledger_entry WHERE business_date = ? AND merchant_id = ?", LEDGER,
                d(p.date()), p.merchantId());
    }

    public List<GatewayRec> gateway(Partition p) {
        return jdbc.query("SELECT * FROM gateway_txn WHERE txn_date = ? AND merchant_id = ?", GATEWAY,
                d(p.date()), p.merchantId());
    }

    /** Bank credits whose UTR matches a settlement_ref of this partition's gateway rows. */
    public List<BankRec> bankByUtr(Partition p, LocalDate asOf) {
        return jdbc.query("""
                SELECT b.* FROM bank_txn b JOIN gateway_txn g ON b.utr = g.settlement_ref
                WHERE g.txn_date = ? AND g.merchant_id = ? AND b.value_date <= ?""", BANK,
                d(p.date()), p.merchantId(), d(asOf));
    }

    /** Credits on this value date that no gateway row references by UTR (or that carry no UTR). */
    public List<BankRec> unreferencedCredits(Partition p, LocalDate asOf) {
        return jdbc.query("""
                SELECT b.* FROM bank_txn b
                WHERE b.value_date = ? AND COALESCE(b.merchant_id, 'UNMAPPED') = ? AND b.value_date <= ?
                  AND (b.utr IS NULL OR NOT EXISTS (SELECT 1 FROM gateway_txn g WHERE g.settlement_ref = b.utr))""",
                BANK, d(p.date()), p.merchantId(), d(asOf));
    }

    /**
     * Fallback candidates for a gateway row without a key match: credits with no UTR, same merchant,
     * exact net amount, value date within [txnDate, txnDate + window] and not after asOf.
     */
    public List<BankRec> fallbackCreditsFor(GatewayRec g, LocalDate asOf, int windowDays) {
        LocalDate until = g.txnDate().plusDays(windowDays).isAfter(asOf) ? asOf : g.txnDate().plusDays(windowDays);
        return jdbc.query("""
                SELECT * FROM bank_txn WHERE utr IS NULL AND merchant_id = ? AND amount_minor = ?
                  AND value_date BETWEEN ? AND ? ORDER BY bank_ref LIMIT 2""", BANK,
                g.merchantId(), g.netMinor(), d(g.txnDate()), d(until));
    }

    /**
     * Gateway rows that could own a UTR-less credit: same merchant, exact net amount, txn date within
     * [valueDate - window, valueDate], and not already matched to a credit by UTR.
     */
    public List<GatewayRec> fallbackGatewayFor(BankRec b, LocalDate asOf, int windowDays) {
        if (b.merchantId() == null) {
            return List.of();
        }
        return jdbc.query("""
                SELECT g.* FROM gateway_txn g
                WHERE g.merchant_id = ? AND g.net_minor = ? AND g.txn_date BETWEEN ? AND ?
                  AND (g.settlement_ref IS NULL OR NOT EXISTS (
                        SELECT 1 FROM bank_txn x WHERE x.utr = g.settlement_ref AND x.value_date <= ?))
                ORDER BY g.gateway_txn_id LIMIT 2""", GATEWAY,
                b.merchantId(), b.amountMinor(), d(b.valueDate().minusDays(windowDays)), d(b.valueDate()), d(asOf));
    }

    /** Mutual-uniqueness fallback: exactly one credit for the row and exactly one row for the credit. */
    public BankRec uniqueFallback(GatewayRec g, LocalDate asOf, int windowDays) {
        List<BankRec> credits = fallbackCreditsFor(g, asOf, windowDays);
        if (credits.size() != 1) {
            return null;
        }
        List<GatewayRec> owners = fallbackGatewayFor(credits.getFirst(), asOf, windowDays);
        return owners.size() == 1 && owners.getFirst().gatewayTxnId().equals(g.gatewayTxnId()) ? credits.getFirst() : null;
    }

    /** True if a UTR-less credit is claimed by exactly one gateway row under the fallback rule. */
    public boolean claimedByFallback(BankRec b, LocalDate asOf, int windowDays) {
        List<GatewayRec> owners = fallbackGatewayFor(b, asOf, windowDays);
        if (owners.size() != 1) {
            return false;
        }
        BankRec back = uniqueFallback(owners.getFirst(), asOf, windowDays);
        return back != null && back.bankRef().equals(b.bankRef());
    }

    private static Date d(LocalDate x) {
        return Date.valueOf(x);
    }
}
