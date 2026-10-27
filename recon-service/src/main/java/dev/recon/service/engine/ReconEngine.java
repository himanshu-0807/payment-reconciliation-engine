package dev.recon.service.engine;

import dev.recon.service.audit.AuditLog;
import dev.recon.service.config.ReconProperties;
import dev.recon.service.domain.ReconRun;
import dev.recon.service.domain.Repositories.ReconRunRepository;
import dev.recon.service.engine.ReconRepository.Partition;
import dev.recon.service.ingest.IngestionService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Runs reconciliation for a date range. Work is partitioned by (business date, merchant) and the
 * partitions execute on a bounded pool of {@code parallelism} threads. Each partition reads, classifies
 * and writes in its own transaction and touches only its own rows, so the outcome is identical for
 * any parallelism; the run digest (SHA-256 over every outcome in partition order) proves it.
 */
@Service
public class ReconEngine {

    private static final Logger log = LoggerFactory.getLogger(ReconEngine.class);

    public record RunRequest(LocalDate from, LocalDate to, LocalDate asOf, Integer parallelism, String actor,
                             boolean skipIngest, Duration ledgerCatchUp) {
    }

    public record PartitionStats(Partition partition, int entities, Map<ResultClass, Integer> byClass,
                                 ExceptionSynchronizer.Stats exceptions, String digest, long millis) {
    }

    public record RunSummary(long runId, String status, LocalDate from, LocalDate to, LocalDate asOf, int parallelism,
                             int partitions, int entities, Map<ResultClass, Integer> byClass,
                             ExceptionSynchronizer.Stats exceptions, IngestionService.Report ingestion,
                             long ledgerLag, String digest, long durationMs, long reconcileMs) {
    }

    private final ReconRepository repo;
    private final PartitionReconciler reconciler;
    private final ResultWriter writer;
    private final ExceptionSynchronizer exceptions;
    private final ExceptionEventPublisher publisher;
    private final IngestionService ingestion;
    private final LedgerCatchUp ledgerCatchUp;
    private final ReconRunRepository runs;
    private final AuditLog audit;
    private final TransactionTemplate tx;
    private final ReconProperties props;
    private final ReentrantLock lock = new ReentrantLock();

    public ReconEngine(ReconRepository repo, PartitionReconciler reconciler, ResultWriter writer,
                       ExceptionSynchronizer exceptions, ExceptionEventPublisher publisher, IngestionService ingestion,
                       LedgerCatchUp ledgerCatchUp, ReconRunRepository runs, AuditLog audit, TransactionTemplate tx,
                       ReconProperties props) {
        this.repo = repo;
        this.reconciler = reconciler;
        this.writer = writer;
        this.exceptions = exceptions;
        this.publisher = publisher;
        this.ingestion = ingestion;
        this.ledgerCatchUp = ledgerCatchUp;
        this.runs = runs;
        this.audit = audit;
        this.tx = tx;
        this.props = props;
    }

    public RunSummary run(RunRequest req) {
        if (!lock.tryLock()) {
            throw new RunInProgressException();
        }
        try {
            return doRun(req);
        } finally {
            lock.unlock();
        }
    }

    private RunSummary doRun(RunRequest req) {
        long t0 = System.nanoTime();
        int parallelism = req.parallelism() == null ? props.parallelism() : req.parallelism();
        if (parallelism < 1 || parallelism > 64) {
            throw new IllegalArgumentException("parallelism must be between 1 and 64");
        }
        long lag = req.ledgerCatchUp() == null ? -1 : ledgerCatchUp.await(req.ledgerCatchUp());
        IngestionService.Report ingest = req.skipIngest() ? null : ingestion.ingestAll(parallelism);

        LocalDate[] range = repo.dataRange();
        LocalDate from = req.from() != null ? req.from() : range == null ? LocalDate.now() : range[0];
        LocalDate to = req.to() != null ? req.to() : req.from() != null ? req.from() : range == null ? from : range[1];
        LocalDate latestBank = repo.latestBankDate();
        LocalDate asOf = req.asOf() != null ? req.asOf() : latestBank != null ? latestBank : to;
        if (to.isBefore(from)) {
            throw new IllegalArgumentException("to must not be before from");
        }
        ReconRun run = runs.save(new ReconRun(from, to, asOf, parallelism, req.actor()));
        long runId = run.getId();
        audit.record(req.actor(), "RUN_STARTED", "RECON_RUN", Long.toString(runId),
                "from " + from + " to " + to + " asOf " + asOf + " parallelism " + parallelism);

        try {
            long r0 = System.nanoTime();
            List<Partition> partitions = repo.partitions(from, to, asOf);
            ReconPolicy policy = ReconPolicy.from(props);
            List<PartitionStats> results = new ArrayList<>(partitions.size());
            try (ExecutorService pool = Executors.newFixedThreadPool(parallelism, Thread.ofPlatform().name("recon-", 0).factory())) {
                List<Future<PartitionStats>> futures = new ArrayList<>(partitions.size());
                for (Partition p : partitions) {
                    futures.add(pool.submit(() -> reconcilePartition(p, asOf, policy, runId)));
                }
                for (Future<PartitionStats> f : futures) {
                    results.add(f.get());
                }
            }
            long reconcileMs = (System.nanoTime() - r0) / 1_000_000;

            Map<ResultClass, Integer> byClass = new EnumMap<>(ResultClass.class);
            ExceptionSynchronizer.Stats exStats = ExceptionSynchronizer.Stats.zero();
            MessageDigest runDigest = sha256();
            int entities = 0;
            for (PartitionStats s : results) {
                entities += s.entities();
                s.byClass().forEach((k, v) -> byClass.merge(k, v, Integer::sum));
                exStats = exStats.plus(s.exceptions());
                runDigest.update((s.partition().date() + "/" + s.partition().merchantId() + "=" + s.digest() + "\n")
                        .getBytes(StandardCharsets.UTF_8));
            }
            String digest = HexFormat.of().formatHex(runDigest.digest());
            ReconRun r = runs.findById(runId).orElseThrow();
            r.complete(partitions.size(), entities, repo.countUnresolvedExceptions(from, to), exStats.autoResolved(), digest);
            runs.save(r);
            long ms = (System.nanoTime() - t0) / 1_000_000;
            audit.record(req.actor(), "RUN_COMPLETED", "RECON_RUN", Long.toString(runId),
                    "partitions " + partitions.size() + " entities " + entities + " digest " + digest);
            log.info("recon run {} completed: partitions={} entities={} parallelism={} reconcile={}ms total={}ms "
                            + "byClass={} exceptions={} digest={}", runId, partitions.size(), entities, parallelism,
                    reconcileMs, ms, byClass, exStats, digest);
            return new RunSummary(runId, r.getStatus(), from, to, asOf, parallelism, partitions.size(), entities, byClass,
                    exStats, ingest, lag, digest, ms, reconcileMs);
        } catch (Exception e) {
            Throwable cause = e instanceof ExecutionException && e.getCause() != null ? e.getCause() : e;
            log.error("recon run {} failed", runId, cause);
            ReconRun r = runs.findById(runId).orElseThrow();
            r.fail(cause.getClass().getSimpleName() + ": " + cause.getMessage());
            runs.save(r);
            audit.record(req.actor(), "RUN_FAILED", "RECON_RUN", Long.toString(runId), cause.getMessage());
            if (cause instanceof RuntimeException re) {
                throw re;
            }
            throw new IllegalStateException(cause);
        }
    }

    private PartitionStats reconcilePartition(Partition p, LocalDate asOf, ReconPolicy policy, long runId) {
        long t0 = System.nanoTime();
        List<ExceptionEvent> events = new ArrayList<>();
        PartitionStats stats = tx.execute(status -> {
            List<Outcome> outcomes = reconciler.reconcile(p, asOf, policy);
            writer.replace(p, outcomes, runId);
            ExceptionSynchronizer.Stats ex = exceptions.sync(p, outcomes, runId, events);
            Map<ResultClass, Integer> byClass = new EnumMap<>(ResultClass.class);
            MessageDigest md = sha256();
            for (Outcome o : outcomes) {
                byClass.merge(o.resultClass(), 1, Integer::sum);
                md.update(o.digestLine().getBytes(StandardCharsets.UTF_8));
                md.update((byte) '\n');
            }
            return new PartitionStats(p, outcomes.size(), byClass, ex, HexFormat.of().formatHex(md.digest()),
                    (System.nanoTime() - t0) / 1_000_000);
        });
        publisher.publish(events);
        return stats;
    }

    private static MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    public static class RunInProgressException extends RuntimeException {
        public RunInProgressException() {
            super("a reconciliation run is already in progress");
        }
    }
}
