package com.toysystem.search.config;

import org.apache.kafka.common.TopicPartition;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.util.backoff.FixedBackOff;

/**
 * 跟 notification-service 同一套容错模式（见其README"死信Topic容错"一节）：
 * ErrorHandlingDeserializer包一层反序列化 + DefaultErrorHandler固定重试 + DeadLetterPublishingRecoverer。
 *
 * 唯一的差异：死信topic名字带上了消费者组名（policy-events.search-service.DLT），
 * 而不是直接用默认的 policy-events.DLT——因为 policy-events 现在有两个完全独立的消费者组
 * （notification-service、search-service），如果两边都发到同一个默认死信topic，
 * 排查问题时没法一眼看出这条死信消息到底是哪个消费者处理失败的。
 */
@Configuration
public class KafkaErrorHandlingConfig {

    private static final String DLT_TOPIC = "policy-events.search-service.DLT";

    @Bean
    public DefaultErrorHandler errorHandler(KafkaTemplate<Object, Object> kafkaTemplate) {
        DeadLetterPublishingRecoverer recoverer = new DeadLetterPublishingRecoverer(
                kafkaTemplate,
                (record, ex) -> new TopicPartition(DLT_TOPIC, -1));
        FixedBackOff backOff = new FixedBackOff(1000L, 3);
        return new DefaultErrorHandler(recoverer, backOff);
    }
}
