package dev.recon.service.engine;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.recon.common.csv.ObjectKeys;
import dev.recon.common.sim.FaultType;
import dev.recon.common.sim.SimulationConfig;
import dev.recon.common.store.ObjectStore;
import dev.recon.service.admin.DataReset;
import dev.recon.service.groundtruth.GroundTruthEvaluator;
import dev.recon.service.support.IntegrationTestBase;
import dev.recon.service.support.SimFeeder;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.core.KafkaTemplate;

import java.time.Duration;
import java.time.LocalDate;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * End-to-end engine tests on real Postgres + Kafka: ground truth, idempotent re-runs, parallelism
 * invariance, and the PENDING_EXPECTED -> escalation lifecycle.
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class ReconEngineIT extends IntegrationTestBase {

    static final LocalDate START = LocalDate.of(2026, 2, 1);

    @Autowired ReconEngine engine;
    @Autowired GroundTruthEvaluator evaluator;
    @Autowired DataReset reset;
    @Autowired KafkaTemplate<String, String> kafka;
    @Autowired ObjectMapper mapper;
    @Autowired ObjectStore store;
    @Autowired JdbcTemplate jdbc;

    static String firstDigest;

    static Map<FaultType, Double> doubledRates() {
        Map<FaultType, Double> m = new EnumMap<>(FaultType.class);
        SimulationConfig.defaultRates().forEach((k, v) -> m.put(k, v * 2));
        return m;
    }

    private void freshWorld(SimulationConfig c, String id) throws Exception {
        reset.resetAll("test");
        store.deletePrefix(ObjectKeys.GATEWAY_PREFIX);
        store.deletePrefix(ObjectKeys.BANK_PREFIX);
        SimFeeder.feed(c, id, kafka, mapper, store);
    }

    private ReconEngine.RunSummary run(Integer parallelism, LocalDate asOf) {
        return engine.run(new ReconEngine.RunRequest(null, null, asOf, parallelism, "test", false, Duration.ofSeconds(60)));
    }

    private List<Map<String, Object>> queueSnapshot() {
        return jdbc.queryForList("SELECT e.entity_type, e.entity_id, e.exception_class, e.reason, e.status, "
                + "e.amount_at_risk_minor, e.resolution_reason, a.amount_minor AS adj, a.status AS adj_status "
                + "FROM recon_exception e LEFT JOIN adjustment_journal a ON a.exception_id = e.id "
                + "ORDER BY e.entity_type, e.entity_id, e.exception_class");
    }

    @Test
    @Order(1)
    void engineFindingsEqualTheInjectedFaults() throws Exception {
        SimulationConfig c = new SimulationConfig(7, START, 3, 4_000, 5, 200, doubledRates(), 6);
        freshWorld(c, "it-gt");
        ReconEngine.RunSummary s = run(4, null);
        assertThat(s.ledgerLag()).isZero();

        GroundTruthEvaluator.Report r = evaluator.evaluate("it-gt");
        System.out.println("GROUND TRUTH " + mapper.writerWithDefaultPrettyPrinter().writeValueAsString(r));
        assertThat(r.sampleMismatches()).isEmpty();
        assertThat(r.precision()).isEqualTo(1.0);
        assertThat(r.recall()).isEqualTo(1.0);
        assertThat(r.byFault()).allSatisfy((f, score) -> assertThat(score.injected()).as(f).isPositive());
        assertThat(r.cleanCorrect()).isEqualTo(r.cleanEntities());
        firstDigest = s.digest();
    }

    @Test
    @Order(2)
    void reRunningTheSameDatesChangesNothing() {
        List<Map<String, Object>> before = queueSnapshot();
        long auditBefore = jdbc.queryForObject("SELECT count(*) FROM audit_log", Long.class);

        ReconEngine.RunSummary again = run(4, null);

        assertThat(again.digest()).isEqualTo(firstDigest);
        assertThat(again.ingestion().filesIngested()).isZero();
        assertThat(again.exceptions().created()).isZero();
        assertThat(queueSnapshot()).isEqualTo(before);
        // Only RUN_STARTED and RUN_COMPLETED are added.
        assertThat(jdbc.queryForObject("SELECT count(*) FROM audit_log", Long.class)).isEqualTo(auditBefore + 2);
    }

    @Test
    @Order(3)
    void parallelismOneAndEightProduceIdenticalResults() throws Exception {
        SimulationConfig c = new SimulationConfig(11, START, 4, 3_000, 5, 200, doubledRates(), 6);
        freshWorld(c, "it-par");
        ReconEngine.RunSummary p1 = run(1, null);
        List<Map<String, Object>> queue1 = queueSnapshot();
        List<Map<String, Object>> results1 = jdbc.queryForList("SELECT * FROM recon_result ORDER BY entity_type, entity_id");

        // Fresh world again so parallelism 8 starts from the same empty queue.
        freshWorld(c, "it-par");
        ReconEngine.RunSummary p8 = run(8, null);
        List<Map<String, Object>> results8 = jdbc.queryForList("SELECT * FROM recon_result ORDER BY entity_type, entity_id");

        assertThat(p1.partitions()).isGreaterThan(8);
        assertThat(p8.digest()).isEqualTo(p1.digest());
        assertThat(queueSnapshot()).isEqualTo(queue1);
        assertThat(stripRunId(results8)).isEqualTo(stripRunId(results1));
        assertThat(evaluator.evaluate("it-par").precision()).isEqualTo(1.0);
    }

    @Test
    @Order(4)
    void missingCreditsWaitAsPendingExpectedAndEscalateWhenGraceExpires() throws Exception {
        Map<FaultType, Double> rates = new EnumMap<>(FaultType.class);
        rates.put(FaultType.MISSING_BANK_RECORD, 0.05);
        rates.put(FaultType.DELAYED_SETTLEMENT, 0.05);
        SimulationConfig c = new SimulationConfig(5, START, 1, 2_000, 5, 200, rates, 6);
        freshWorld(c, "it-grace");
        long missing = jdbc.queryForObject("SELECT 0", Long.class);

        // As of T+2 the delayed (T+3..T+5) and the missing credits are all still expected.
        run(4, START.plusDays(2));
        long pendingEarly = count("MISSING_IN_BANK", "PENDING_EXPECTED");
        assertThat(count("MISSING_IN_BANK", "OPEN")).isZero();

        // As of T+6 the grace window (T+5) is over: delayed credits arrived, missing ones escalate.
        run(4, START.plusDays(6));
        long escalated = count("MISSING_IN_BANK", "OPEN");
        long lateWithinGrace = count("LATE_SETTLEMENT", "AUTO_RESOLVED");
        long injectedMissing = jdbc.queryForObject("SELECT count(*) FROM recon_result WHERE reason = 'NOT_CREDITED'", Long.class);

        assertThat(escalated).isEqualTo(injectedMissing).isPositive();
        assertThat(lateWithinGrace).isPositive();
        assertThat(pendingEarly).isEqualTo(escalated + lateWithinGrace);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM recon_exception WHERE exception_class = 'MISSING_IN_BANK' "
                + "AND resolution_reason = 'SUPERSEDED'", Long.class)).isEqualTo(lateWithinGrace);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM audit_log WHERE action = 'ESCALATED'", Long.class))
                .isGreaterThanOrEqualTo(escalated);
        assertThat(missing).isZero();
    }

    private long count(String cls, String status) {
        return jdbc.queryForObject("SELECT count(*) FROM recon_exception WHERE exception_class = ? AND status = ?",
                Long.class, cls, status);
    }

    private static List<Map<String, Object>> stripRunId(List<Map<String, Object>> rows) {
        rows.forEach(r -> r.remove("run_id"));
        return rows;
    }
}
