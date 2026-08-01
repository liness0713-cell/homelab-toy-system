package com.toysystem.notification.config;

import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.TopicBuilder;

/**
 * 死信topic也显式声明，跟 policy-service 里对 policy-events 本身的做法保持一致，
 * 不依赖broker的auto-create。死信消息量预期很小，1个分区足够。
 */
@Configuration
public class KafkaTopicConfig {

    @Bean
    public NewTopic policyEventsDeadLetterTopic() {
        return TopicBuilder.name("policy-events.DLT")
                .partitions(1)
                .replicas(1)
                .build();
    }
}
