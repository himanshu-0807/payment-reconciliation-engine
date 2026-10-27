package dev.recon.service.config;

import dev.recon.common.event.GatewayCallback;
import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.TopicBuilder;

@Configuration
public class KafkaTopicsConfig {

    public static final String EXCEPTIONS_TOPIC = "recon.exceptions";

    @Bean
    NewTopic callbacksTopic() {
        return TopicBuilder.name(GatewayCallback.TOPIC).partitions(6).replicas(1).build();
    }

    @Bean
    NewTopic exceptionsTopic() {
        return TopicBuilder.name(EXCEPTIONS_TOPIC).partitions(3).replicas(1).build();
    }
}
