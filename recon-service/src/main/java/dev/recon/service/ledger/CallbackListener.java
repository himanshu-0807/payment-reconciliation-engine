package dev.recon.service.ledger;

import dev.recon.common.event.GatewayCallback;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

/** Batch consumer of {@code gateway.callbacks}; offsets are committed after the DB transaction. */
@Component
public class CallbackListener {

    private static final Logger log = LoggerFactory.getLogger(CallbackListener.class);
    /** Set explicitly: by default Spring Kafka would use the listener id as the group id. */
    public static final String GROUP_ID = "recon-ledger";

    private final CallbackBatchProcessor processor;
    private final AtomicLong applied = new AtomicLong();
    private final AtomicLong duplicates = new AtomicLong();

    public CallbackListener(CallbackBatchProcessor processor) {
        this.processor = processor;
    }

    @KafkaListener(id = "ledger-callbacks", groupId = CallbackListener.GROUP_ID, topics = GatewayCallback.TOPIC, batch = "true",
            concurrency = "${recon.ledger.consumer-concurrency:3}")
    public void onBatch(List<ConsumerRecord<String, String>> records) {
        CallbackBatchProcessor.Result r = processor.process(records);
        long a = applied.addAndGet(r.applied());
        long d = duplicates.addAndGet(r.duplicates());
        if (r.duplicates() > 0 || r.rejected() > 0 || r.redeliveries() > 0) {
            log.info("ledger batch size={} applied={} duplicates={} redeliveries={} rejected={}",
                    records.size(), r.applied(), r.duplicates(), r.redeliveries(), r.rejected());
        }
        log.debug("ledger totals applied={} duplicates={}", a, d);
    }

    public long appliedTotal() {
        return applied.get();
    }

    public long duplicatesTotal() {
        return duplicates.get();
    }
}
