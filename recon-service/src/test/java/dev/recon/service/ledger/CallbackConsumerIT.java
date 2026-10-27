package dev.recon.service.ledger;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.recon.common.event.GatewayCallback;
import dev.recon.service.support.IntegrationTestBase;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.core.KafkaTemplate;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

class CallbackConsumerIT extends IntegrationTestBase {

    @Autowired KafkaTemplate<String, String> kafka;
    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper mapper;
    @Autowired CallbackBatchProcessor processor;

    private final String run = UUID.randomUUID().toString().substring(0, 8);
    private final Instant t0 = Instant.parse("2026-01-05T06:00:00Z");

    @BeforeEach
    void clean() {
        jdbc.update("DELETE FROM duplicate_delivery");
        jdbc.update("DELETE FROM processed_event");
        jdbc.update("DELETE FROM ledger_entry");
    }

    private GatewayCallback ev(String paymentId, String status, int minutes) {
        UUID id = UUID.nameUUIDFromBytes((run + paymentId + status).getBytes());
        return new GatewayCallback(id, paymentId, "G-" + paymentId, "M001", LocalDate.of(2026, 1, 5), status,
                50_000, "INR", t0.plusSeconds(60L * minutes));
    }

    private void send(GatewayCallback e) throws Exception {
        kafka.send(GatewayCallback.TOPIC, e.paymentId(), mapper.writeValueAsString(e)).get(10, TimeUnit.SECONDS);
    }

    private Map<String, Object> ledger(String paymentId) {
        return jdbc.queryForMap("SELECT status, duplicate_deliveries, out_of_order_events, amount_minor "
                + "FROM ledger_entry WHERE payment_id = ?", paymentId);
    }

    private long count(String table) {
        return jdbc.queryForObject("SELECT count(*) FROM " + table, Long.class);
    }

    @Test
    void consumesCallbacksIdempotentlyAndSurvivesOffsetReset() throws Exception {
        String a = run + "-A", b = run + "-B", c = run + "-C";
        send(ev(a, "SUCCESS", 0));
        GatewayCallback dup = ev(b, "SUCCESS", 0);
        send(dup);
        send(dup);
        send(dup);
        send(ev(c, "SUCCESS", 30));   // timeout-then-success: newest first...
        send(ev(c, "PENDING", 0));    // ...then the stale PENDING arrives
        kafka.send(GatewayCallback.TOPIC, run + "-bad", "{not json").get(10, TimeUnit.SECONDS);

        await().atMost(Duration.ofSeconds(30)).until(() -> count("processed_event") == 4
                && count("duplicate_delivery") == 2);

        assertThat(ledger(a)).containsEntry("status", "SUCCESS").containsEntry("duplicate_deliveries", 0);
        assertThat(ledger(b)).containsEntry("status", "SUCCESS").containsEntry("duplicate_deliveries", 2);
        assertThat(ledger(c)).containsEntry("status", "SUCCESS").containsEntry("out_of_order_events", 1);
        List<Map<String, Object>> before = jdbc.queryForList("SELECT * FROM ledger_entry ORDER BY payment_id");

        // Re-read the whole topic from offset 0 (what a consumer restart or offset reset does) and feed
        // the real records, with their original partitions/offsets, through the processor again.
        List<ConsumerRecord<String, String>> replay = readAllFromBeginning();
        assertThat(replay).hasSize(7);
        CallbackBatchProcessor.Result again = processor.process(replay);
        assertThat(again.applied()).isZero();
        assertThat(again.duplicates()).isZero();
        assertThat(again.redeliveries()).isEqualTo(6);
        assertThat(again.rejected()).isEqualTo(1);

        assertThat(jdbc.queryForList("SELECT * FROM ledger_entry ORDER BY payment_id")).isEqualTo(before);
        assertThat(count("duplicate_delivery")).isEqualTo(2);
        assertThat(count("processed_event")).isEqualTo(4);
    }

    @Test
    void processorTreatsSamePositionAsRedeliveryAndOtherPositionAsDuplicate() throws Exception {
        GatewayCallback e = ev(run + "-D", "SUCCESS", 0);
        String json = mapper.writeValueAsString(e);
        ConsumerRecord<String, String> first = new ConsumerRecord<>(GatewayCallback.TOPIC, 0, 900_001L, e.paymentId(), json);
        ConsumerRecord<String, String> again = new ConsumerRecord<>(GatewayCallback.TOPIC, 0, 900_001L, e.paymentId(), json);
        ConsumerRecord<String, String> copy = new ConsumerRecord<>(GatewayCallback.TOPIC, 0, 900_002L, e.paymentId(), json);

        assertThat(processor.process(List.of(first))).isEqualTo(new CallbackBatchProcessor.Result(1, 0, 0, 0));
        assertThat(processor.process(List.of(again))).isEqualTo(new CallbackBatchProcessor.Result(0, 0, 1, 0));
        assertThat(processor.process(List.of(copy))).isEqualTo(new CallbackBatchProcessor.Result(0, 1, 0, 0));
        assertThat(processor.process(List.of(copy, again))).isEqualTo(new CallbackBatchProcessor.Result(0, 0, 2, 0));
        assertThat(ledger(e.paymentId())).containsEntry("duplicate_deliveries", 1).containsEntry("amount_minor", 50_000L);
    }

    @Test
    void duplicateInsideOneBatchIsCountedOnce() throws Exception {
        GatewayCallback e = ev(run + "-E", "SUCCESS", 0);
        String json = mapper.writeValueAsString(e);
        var r1 = new ConsumerRecord<>(GatewayCallback.TOPIC, 1, 800_001L, e.paymentId(), json);
        var r2 = new ConsumerRecord<>(GatewayCallback.TOPIC, 1, 800_002L, e.paymentId(), json);
        assertThat(processor.process(List.of(r1, r2))).isEqualTo(new CallbackBatchProcessor.Result(1, 1, 0, 0));
        assertThat(ledger(e.paymentId())).containsEntry("duplicate_deliveries", 1);
    }

    /** Reads the whole topic up to its current end and keeps this test's records (other tests share the topic). */
    private List<ConsumerRecord<String, String>> readAllFromBeginning() {
        Map<String, Object> props = Map.of(
                ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, KAFKA.getBootstrapServers(),
                ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, false,
                ConsumerConfig.MAX_POLL_RECORDS_CONFIG, 5_000);
        List<ConsumerRecord<String, String>> out = new ArrayList<>();
        try (KafkaConsumer<String, String> c = new KafkaConsumer<>(props, new StringDeserializer(), new StringDeserializer())) {
            List<TopicPartition> parts = c.partitionsFor(GatewayCallback.TOPIC).stream()
                    .map(p -> new TopicPartition(p.topic(), p.partition())).toList();
            c.assign(parts);
            c.seekToBeginning(parts);
            Map<TopicPartition, Long> end = c.endOffsets(parts);
            long deadline = System.currentTimeMillis() + 60_000;
            while (parts.stream().anyMatch(tp -> c.position(tp) < end.get(tp)) && System.currentTimeMillis() < deadline) {
                c.poll(Duration.ofMillis(500)).forEach(r -> {
                    if (r.key() != null && r.key().startsWith(run)) {
                        out.add(r);
                    }
                });
            }
        }
        return out;
    }
}
