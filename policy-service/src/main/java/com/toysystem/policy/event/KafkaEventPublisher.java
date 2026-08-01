package com.toysystem.policy.event;

import com.toysystem.event.PolicyEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
@Slf4j
public class KafkaEventPublisher implements EventPublisher {

    public static final String TOPIC = "policy-events";

    private final KafkaTemplate<String, PolicyEvent> kafkaTemplate;

    @Override
    public void publish(PolicyEvent event) {
        // 用policyNo做key，保证同一张保单的多次事件落在同一个分区、消费顺序不乱
        String key = event.getPolicy().getPolicyNo();
        kafkaTemplate.send(TOPIC, key, event)
                .whenComplete((result, ex) -> {
                    if (ex != null) {
                        log.error("Failed to publish {} for policyNo={}", event.getEventType(), key, ex);
                    } else {
                        log.debug("Published {} for policyNo={} to partition={}",
                                event.getEventType(), key, result.getRecordMetadata().partition());
                    }
                });
    }
}
