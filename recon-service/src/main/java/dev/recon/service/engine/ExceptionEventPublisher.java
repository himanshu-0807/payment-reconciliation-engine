package dev.recon.service.engine;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.recon.service.config.KafkaTopicsConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

import java.util.List;

/** Publishes exception lifecycle events to recon.exceptions after the partition has committed. */
@Component
public class ExceptionEventPublisher {

    private static final Logger log = LoggerFactory.getLogger(ExceptionEventPublisher.class);

    private final KafkaTemplate<String, String> kafka;
    private final ObjectMapper mapper;

    public ExceptionEventPublisher(KafkaTemplate<String, String> kafka, ObjectMapper mapper) {
        this.kafka = kafka;
        this.mapper = mapper;
    }

    public void publish(List<ExceptionEvent> events) {
        for (ExceptionEvent e : events) {
            try {
                kafka.send(KafkaTopicsConfig.EXCEPTIONS_TOPIC, e.entityId(), mapper.writeValueAsString(e))
                        .whenComplete((r, ex) -> {
                            if (ex != null) {
                                log.warn("failed to publish exception event {}: {}", e.exceptionId(), ex.getMessage());
                            }
                        });
            } catch (JsonProcessingException ex) {
                log.warn("cannot serialise exception event {}", e.exceptionId(), ex);
            }
        }
    }
}
