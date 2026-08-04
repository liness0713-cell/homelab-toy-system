package com.toysystem.notification.config;

import org.apache.kafka.common.TopicPartition;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.util.backoff.FixedBackOff;

/**
 * 消费失败的统一容错策略（P3踩坑后补上，见 docs/homelab-toy-system-plan.md 6.1节）：
 * 1. 反序列化失败（脏消息，见ErrorHandlingDeserializer配置）和业务代码抛异常，
 *    都会先重试固定次数；
 * 2. 重试用尽后，原始消息被发布到死信topic，不再无限卡在原topic的这个offset上，
 *    后面的正常消息能继续被消费。
 *
 * Spring Boot会自动把这个 DefaultErrorHandler bean 接到自动配置的
 * ConcurrentKafkaListenerContainerFactory 上，不用手写container factory。
 */
@Configuration
public class KafkaErrorHandlingConfig {

    // P4加了search-service之后，policy-events多了一个完全独立的消费者组。死信topic名字
    // 带上消费者组名（而不是用默认的"<topic>.DLT"），这样两边失败的消息不会堆到同一个
    // topic里分不清是谁处理失败的——search-service那边也是同一个约定，见它的README。
    private static final String DLT_TOPIC = "policy-events.notification-service.DLT";

    @Bean
    public DefaultErrorHandler errorHandler(KafkaTemplate<Object, Object> kafkaTemplate) {
        DeadLetterPublishingRecoverer recoverer = new DeadLetterPublishingRecoverer(
                kafkaTemplate,
                // 死信topic只开了1个分区（死信量预期很小），但原topic policy-events有3个分区；
                // Recoverer默认想把消息发到"跟原分区号相同"的分区，对不上，所以显式声明
                // "分区号-1"=交给Kafka自己的分区器决定，不用再对齐分区号。
                (record, ex) -> new TopicPartition(DLT_TOPIC, -1));
        // 重试3次，每次间隔1秒；用尽后交给上面的recoverer发到死信topic
        FixedBackOff backOff = new FixedBackOff(1000L, 3);
        return new DefaultErrorHandler(recoverer, backOff);
    }
}
