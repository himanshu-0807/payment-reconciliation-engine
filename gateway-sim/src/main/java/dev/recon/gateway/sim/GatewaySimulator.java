package dev.recon.gateway.sim;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.recon.common.csv.GatewaySettlementRow;
import dev.recon.common.csv.ObjectKeys;
import dev.recon.common.event.GatewayCallback;
import dev.recon.common.sim.FaultType;
import dev.recon.common.sim.GroundTruthRecord;
import dev.recon.common.sim.SimRunRequest;
import dev.recon.common.sim.SimulationConfig;
import dev.recon.common.sim.WorldGenerator;
import dev.recon.common.store.ObjectStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import java.io.BufferedWriter;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Runs a simulation day by day: publishes the day's callbacks to Kafka (including injected duplicate
 * and out-of-order callbacks), writes the gateway settlement CSV to the object store, and finally
 * asks bank-sim to produce the matching bank statements. The ground truth is written last.
 */
@Service
public class GatewaySimulator {

    private static final Logger log = LoggerFactory.getLogger(GatewaySimulator.class);

    private final KafkaTemplate<String, String> kafka;
    private final ObjectStore store;
    private final ObjectMapper mapper;
    private final RestClient bankSim;
    private final ExecutorService executor = Executors.newSingleThreadExecutor(r -> new Thread(r, "simulator"));
    private final Map<String, SimRunStatus> runs = new LinkedHashMap<>();
    private final AtomicReference<String> active = new AtomicReference<>();

    public GatewaySimulator(KafkaTemplate<String, String> kafka, ObjectStore store, ObjectMapper mapper,
                            RestClient.Builder rest, @Value("${sim.bank-sim-url}") String bankSimUrl) {
        this.kafka = kafka;
        this.store = store;
        this.mapper = mapper;
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory();
        factory.setReadTimeout(Duration.ofMinutes(30));
        this.bankSim = rest.baseUrl(bankSimUrl).requestFactory(factory).build();
    }

    public synchronized SimRunStatus start(SimRunRequest request) {
        if (active.get() != null) {
            throw new IllegalStateException("simulation " + active.get() + " is still running");
        }
        SimulationConfig config = request.toConfig(LocalDate.now(WorldGenerator.ZONE).minusDays(20));
        String id = request.simRunId() != null ? request.simRunId()
                : "sim-" + config.seed() + "-" + UUID.randomUUID().toString().substring(0, 8);
        SimRunStatus status = new SimRunStatus(id, SimRunStatus.RUNNING, config, Instant.now(), null, 0, 0, 0, 0, 0,
                Map.of(), ObjectKeys.groundTruth(id), null);
        runs.put(id, status);
        active.set(id);
        SimRunRequest bankRequest = new SimRunRequest(config.seed(), config.startDate(), config.days(),
                config.paymentsPerDay(), config.merchants(), config.faultRates(), id);
        executor.submit(() -> execute(id, config, bankRequest));
        return status;
    }

    public synchronized SimRunStatus status(String id) {
        return runs.get(id);
    }

    public synchronized List<SimRunStatus> all() {
        return List.copyOf(runs.values()).reversed();
    }

    private synchronized void update(String id, SimRunStatus s) {
        runs.put(id, s);
    }

    private void execute(String id, SimulationConfig config, SimRunRequest bankRequest) {
        long t0 = System.nanoTime();
        WorldGenerator gen = new WorldGenerator(config);
        Map<FaultType, Long> faults = new EnumMap<>(FaultType.class);
        AtomicLong published = new AtomicLong();
        AtomicLong sendErrors = new AtomicLong();
        long payments = 0, gwRows = 0;
        Path truthFile = null;
        try {
            store.deletePrefix(ObjectKeys.GATEWAY_PREFIX);
            truthFile = Files.createTempFile("ground-truth-", ".csv");
            try (BufferedWriter truth = Files.newBufferedWriter(truthFile, StandardCharsets.UTF_8)) {
                truth.write(GroundTruthRecord.HEADER);
                truth.newLine();
                for (int d = 0; d < config.days(); d++) {
                    LocalDate date = config.startDate().plusDays(d);
                    WorldGenerator.DaySlice day = gen.generateDay(date);
                    for (GatewayCallback cb : day.callbacks()) {
                        kafka.send(GatewayCallback.TOPIC, cb.paymentId(), json(cb)).whenComplete((r, ex) -> {
                            if (ex != null) {
                                sendErrors.incrementAndGet();
                            } else {
                                published.incrementAndGet();
                            }
                        });
                    }
                    writeGatewayFile(date, day.gatewayRows());
                    for (GroundTruthRecord g : day.groundTruth()) {
                        truth.write(g.toCsv());
                        truth.newLine();
                        faults.merge(g.fault(), 1L, Long::sum);
                    }
                    kafka.flush();
                    payments += config.paymentsPerDay();
                    gwRows += day.gatewayRows().size();
                    update(id, progress(id, config, d + 1, payments, published.get(), gwRows, 0, faults, null, null));
                    log.info("sim {} day {} done: {} payments, {} callbacks queued", id, date, payments,
                            day.callbacks().size());
                }
            }
            kafka.flush();
            if (sendErrors.get() > 0) {
                throw new IllegalStateException(sendErrors.get() + " callbacks failed to publish");
            }
            Map<?, ?> bank = Objects.requireNonNull(bankSim.post().uri("/sim/bank/generate").body(bankRequest)
                    .retrieve().body(Map.class));
            long bankRows = ((Number) bank.get("rowsWritten")).longValue();
            store.put(ObjectKeys.groundTruth(id), truthFile);
            store.put(ObjectKeys.GROUND_TRUTH_PREFIX + "latest.txt", id.getBytes(StandardCharsets.UTF_8));
            update(id, progress(id, config, config.days(), payments, published.get(), gwRows, bankRows, faults,
                    Instant.now(), null));
            log.info("sim {} completed in {} ms: payments={} callbacks={} gatewayRows={} bankRows={} faults={}", id,
                    (System.nanoTime() - t0) / 1_000_000, payments, published.get(), gwRows, bankRows, faults);
        } catch (Exception e) {
            log.error("sim {} failed", id, e);
            update(id, progress(id, config, 0, payments, published.get(), gwRows, 0, faults, Instant.now(),
                    e.getClass().getSimpleName() + ": " + e.getMessage()));
        } finally {
            active.set(null);
            if (truthFile != null) {
                try {
                    Files.deleteIfExists(truthFile);
                } catch (IOException ignored) {
                    // temp file
                }
            }
        }
    }

    private SimRunStatus progress(String id, SimulationConfig config, int days, long payments, long callbacks,
                                  long gwRows, long bankRows, Map<FaultType, Long> faults, Instant finished,
                                  String error) {
        SimRunStatus prev = status(id);
        String state = error != null ? SimRunStatus.FAILED : finished != null ? SimRunStatus.COMPLETED : SimRunStatus.RUNNING;
        return new SimRunStatus(id, state, config, prev.startedAt(), finished, days, payments, callbacks, gwRows,
                bankRows, Map.copyOf(faults), prev.groundTruthKey(), error);
    }

    private void writeGatewayFile(LocalDate date, List<GatewaySettlementRow> rows) throws IOException {
        Path tmp = Files.createTempFile("gw-", ".csv");
        try {
            try (BufferedWriter w = Files.newBufferedWriter(tmp, StandardCharsets.UTF_8)) {
                w.write(GatewaySettlementRow.HEADER);
                w.newLine();
                for (GatewaySettlementRow r : rows) {
                    w.write(r.toCsv());
                    w.newLine();
                }
            }
            store.put(ObjectKeys.gatewayFile(date), tmp);
        } finally {
            Files.deleteIfExists(tmp);
        }
    }

    private String json(GatewayCallback cb) {
        try {
            return mapper.writeValueAsString(cb);
        } catch (JsonProcessingException e) {
            throw new UncheckedIOException(e);
        }
    }
}
