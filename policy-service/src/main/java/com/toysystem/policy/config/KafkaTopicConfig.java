package com.toysystem.policy.config;

import com.toysystem.policy.event.KafkaEventPublisher;
import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.TopicBuilder;

/**
 * 显式声明topic而不是依赖broker的auto-create——生产环境通常会关掉自动建topic，
 * 这里提前养成习惯。3个分区是为将来多个消费者组并行消费留余量。
 */
@Configuration
public class KafkaTopicConfig {

    @Bean
    public NewTopic policyEventsTopic() {
        return TopicBuilder.name(KafkaEventPublisher.TOPIC)
                .partitions(3)
                .replicas(1)
                .build();
    }
}
