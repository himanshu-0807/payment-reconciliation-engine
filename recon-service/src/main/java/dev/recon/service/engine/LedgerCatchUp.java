package dev.recon.service.engine;

import dev.recon.common.event.GatewayCallback;
import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.admin.OffsetSpec;
import org.apache.kafka.common.TopicPartition;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.core.KafkaAdmin;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.HashMap;
import java.util.Map;

/**
 * Waits until the ledger consumer group has committed up to the end of gateway.callbacks, so a run
 * does not report MISSING_IN_LEDGER for callbacks that are merely still in flight.
 */
@Component
public class LedgerCatchUp {

    private static final Logger log = LoggerFactory.getLogger(LedgerCatchUp.class);
    private static final String GROUP = dev.recon.service.ledger.CallbackListener.GROUP_ID;

    private final KafkaAdmin kafkaAdmin;

    public LedgerCatchUp(KafkaAdmin kafkaAdmin) {
        this.kafkaAdmin = kafkaAdmin;
    }

    /** @return remaining lag (0 when caught up) after waiting at most {@code timeout} */
    public long await(Duration timeout) {
        long deadline = System.nanoTime() + timeout.toNanos();
        long lag = -1;
        try (AdminClient admin = AdminClient.create(kafkaAdmin.getConfigurationProperties())) {
            while (true) {
                lag = lag(admin);
                if (lag == 0 || System.nanoTime() > deadline) {
                    break;
                }
                Thread.sleep(500);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (Exception e) {
            log.warn("could not determine ledger consumer lag: {}", e.getMessage());
            return -1;
        }
        if (lag > 0) {
            log.warn("ledger consumer still {} records behind after {}", lag, timeout);
        }
        return lag;
    }

    private long lag(AdminClient admin) throws Exception {
        var description = admin.describeTopics(java.util.List.of(GatewayCallback.TOPIC)).allTopicNames().get();
        Map<TopicPartition, OffsetSpec> request = new HashMap<>();
        description.get(GatewayCallback.TOPIC).partitions()
                .forEach(p -> request.put(new TopicPartition(GatewayCallback.TOPIC, p.partition()), OffsetSpec.latest()));
        var ends = admin.listOffsets(request).all().get();
        var committed = admin.listConsumerGroupOffsets(GROUP).partitionsToOffsetAndMetadata().get();
        long lag = 0;
        for (var e : ends.entrySet()) {
            var c = committed.get(e.getKey());
            lag += e.getValue().offset() - (c == null ? 0 : c.offset());
        }
        return lag;
    }
}
