package dev.recon.gateway.sim;

import dev.recon.common.event.GatewayCallback;
import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.TopicBuilder;

@Configuration
public class KafkaTopics {

    @Bean
    NewTopic callbacksTopic() {
        return TopicBuilder.name(GatewayCallback.TOPIC).partitions(6).replicas(1).build();
    }
}
