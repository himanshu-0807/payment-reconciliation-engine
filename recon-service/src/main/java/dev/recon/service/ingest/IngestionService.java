package dev.recon.service.ingest;

import dev.recon.common.csv.BankStatementRow;
import dev.recon.common.csv.GatewaySettlementRow;
import dev.recon.common.csv.ObjectKeys;
import dev.recon.common.store.ObjectStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.sql.Date;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Pulls gateway settlement files and bank statements from the object store into Postgres.
 *
 * <p>Idempotent at two levels: a file already ingested with the same checksum is skipped, and every
 * row is inserted with {@code ON CONFLICT DO NOTHING} on its natural key (gateway_txn_id, bank_ref),
 * so re-ingesting a file - or the same rows in a re-written file - never duplicates data. Two bank
 * rows with different bank_ref and the same UTR are both kept: that is a real duplicate credit.
 */
@Service
public class IngestionService {

    private static final Logger log = LoggerFactory.getLogger(IngestionService.class);
    private static final int BATCH = 5_000;
    public static final String GATEWAY = "GATEWAY";
    public static final String BANK = "BANK";

    public record FileReport(String source, String key, int rows, int inserted, int rejected, boolean skipped) {
    }

    public record Report(int filesSeen, int filesIngested, long rowsRead, long rowsInserted, long rowsRejected,
                         long durationMs, List<FileReport> files) {
    }

    private final ObjectStore store;
    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;

    public IngestionService(ObjectStore store, JdbcTemplate jdbc, TransactionTemplate tx) {
        this.store = store;
        this.jdbc = jdbc;
        this.tx = tx;
    }

    public Report ingestAll(int parallelism) {
        long t0 = System.nanoTime();
        List<String[]> work = new ArrayList<>();
        try {
            for (ObjectStore.ObjectInfo o : store.list(ObjectKeys.GATEWAY_PREFIX)) {
                work.add(new String[]{GATEWAY, o.key(), checksum(o)});
            }
            for (ObjectStore.ObjectInfo o : store.list(ObjectKeys.BANK_PREFIX)) {
                work.add(new String[]{BANK, o.key(), checksum(o)});
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        List<FileReport> reports = new ArrayList<>();
        try (ExecutorService pool = Executors.newFixedThreadPool(Math.max(1, parallelism))) {
            List<Future<FileReport>> futures = new ArrayList<>();
            for (String[] w : work) {
                futures.add(pool.submit(() -> ingestFile(w[0], w[1], w[2])));
            }
            for (Future<FileReport> f : futures) {
                reports.add(f.get());
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("ingestion interrupted", e);
        } catch (ExecutionException e) {
            throw new IllegalStateException("ingestion failed: " + e.getCause().getMessage(), e.getCause());
        }
        long ms = (System.nanoTime() - t0) / 1_000_000;
        Report r = new Report(reports.size(), (int) reports.stream().filter(x -> !x.skipped()).count(),
                reports.stream().mapToLong(FileReport::rows).sum(), reports.stream().mapToLong(FileReport::inserted).sum(),
                reports.stream().mapToLong(FileReport::rejected).sum(), ms, reports);
        log.info("ingestion: files seen={} ingested={} rows read={} inserted={} rejected={} in {} ms",
                r.filesSeen(), r.filesIngested(), r.rowsRead(), r.rowsInserted(), r.rowsRejected(), ms);
        return r;
    }

    /** bank/statements/2026-01-05.csv -> 2026-01-05; null if the key does not carry a date. */
    static Date statementDate(String key) {
        Matcher m = DATE_IN_KEY.matcher(key);
        return m.find() ? Date.valueOf(m.group(1)) : null;
    }

    private static final Pattern DATE_IN_KEY = Pattern.compile("(\\d{4}-\\d{2}-\\d{2})\\.csv$");

    private static String checksum(ObjectStore.ObjectInfo o) {
        return o.etag() == null || o.etag().isBlank() ? "size-" + o.size() : o.etag();
    }

    FileReport ingestFile(String source, String key, String checksum) {
        Integer seen = jdbc.queryForObject("SELECT count(*) FROM ingested_file WHERE source = ? AND object_key = ? "
                + "AND checksum = ?", Integer.class, source, key, checksum);
        if (seen != null && seen > 0) {
            return new FileReport(source, key, 0, 0, 0, true);
        }
        return tx.execute(status -> {
            int[] counts = new int[3]; // rows, inserted, rejected
            List<Object[]> batch = new ArrayList<>(BATCH);
            String sql = GATEWAY.equals(source) ? GATEWAY_INSERT : BANK_INSERT;
            try (BufferedReader r = new BufferedReader(new InputStreamReader(store.get(key), StandardCharsets.UTF_8))) {
                String header = r.readLine();
                String expected = GATEWAY.equals(source) ? GatewaySettlementRow.HEADER : BankStatementRow.HEADER;
                if (header == null || !header.trim().equals(expected)) {
                    throw new IllegalArgumentException(key + ": unexpected header " + header);
                }
                String line;
                int lineNo = 1;
                while ((line = r.readLine()) != null) {
                    lineNo++;
                    if (line.isBlank()) {
                        continue;
                    }
                    counts[0]++;
                    try {
                        batch.add(GATEWAY.equals(source) ? gatewayArgs(GatewaySettlementRow.parse(line), key)
                                : bankArgs(BankStatementRow.parse(line), key));
                    } catch (RuntimeException e) {
                        counts[2]++;
                        log.warn("{} line {} rejected: {}", key, lineNo, e.getMessage());
                    }
                    if (batch.size() == BATCH) {
                        counts[1] += flush(sql, batch);
                    }
                }
                counts[1] += flush(sql, batch);
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
            jdbc.update("INSERT INTO ingested_file (source, object_key, checksum, row_count, inserted_count, statement_date) "
                    + "VALUES (?,?,?,?,?,?) ON CONFLICT DO NOTHING", source, key, checksum, counts[0], counts[1],
                    statementDate(key));
            return new FileReport(source, key, counts[0], counts[1], counts[2], false);
        });
    }

    private int flush(String sql, List<Object[]> batch) {
        if (batch.isEmpty()) {
            return 0;
        }
        int inserted = 0;
        for (int n : jdbc.batchUpdate(sql, batch)) {
            // With reWriteBatchedInserts the driver reports SUCCESS_NO_INFO (-2) per row.
            inserted += n == -2 ? 1 : n;
        }
        batch.clear();
        return inserted;
    }

    private static final String GATEWAY_INSERT = "INSERT INTO gateway_txn (gateway_txn_id, merchant_order_id, "
            + "merchant_id, txn_date, settlement_date, gross_minor, fee_minor, net_minor, currency, settlement_ref, "
            + "source_file) VALUES (?,?,?,?,?,?,?,?,?,?,?) ON CONFLICT (gateway_txn_id) DO NOTHING";

    private static final String BANK_INSERT = "INSERT INTO bank_txn (bank_ref, account_no, merchant_id, value_date, "
            + "amount_minor, currency, utr, narration, source_file) VALUES (?,?,?,?,?,?,?,?,?) "
            + "ON CONFLICT (bank_ref) DO NOTHING";

    private static Object[] gatewayArgs(GatewaySettlementRow g, String key) {
        validate(g.grossMinor() >= 0 && g.feeMinor() >= 0 && g.netMinor() == g.grossMinor() - g.feeMinor(),
                "net must equal gross - fee and amounts must be non-negative");
        validate(g.currency().length() == 3, "currency must be ISO-4217");
        return new Object[]{g.gatewayTxnId(), g.merchantOrderId(), g.merchantId(), Date.valueOf(g.txnDate()),
                Date.valueOf(g.settlementDate()), g.grossMinor(), g.feeMinor(), g.netMinor(), g.currency(),
                g.settlementRef(), key};
    }

    private static Object[] bankArgs(BankStatementRow b, String key) {
        validate(b.amountMinor() >= 0, "credit amount must be non-negative");
        validate(b.currency().length() == 3, "currency must be ISO-4217");
        return new Object[]{b.bankRef(), b.accountNo(), BankStatementRow.merchantForAccount(b.accountNo()),
                Date.valueOf(b.valueDate()), b.amountMinor(), b.currency(), b.utr(), b.narration(), key};
    }

    private static void validate(boolean ok, String msg) {
        if (!ok) {
            throw new IllegalArgumentException(msg);
        }
    }
}
