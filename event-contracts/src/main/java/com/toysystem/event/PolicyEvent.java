package com.toysystem.event;

import lombok.Data;

import java.io.Serializable;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * policy-events topic 的消息体，字段/命名严格对齐 docs/kafka-event-schema.md。
 * 这是生产者（policy-service）和消费者（notification-service、后续的search-service）
 * 共享的同一个类——不再各自维护一份重复定义，这是P3"坑1"的根本解法（见2.1节）。
 *
 * 特意不依赖任何具体服务的实体类（比如policy-service的Policy），保持这个模块是
 * "纯契约"，不会反向依赖回某个业务服务。
 */
@Data
public class PolicyEvent implements Serializable {

    private String eventId;
    private PolicyEventType eventType;
    private Instant occurredAt;
    private PolicyPayload policy;

    public static PolicyEvent of(PolicyEventType eventType, PolicyPayload policy) {
        PolicyEvent event = new PolicyEvent();
        event.setEventId(UUID.randomUUID().toString());
        event.setEventType(eventType);
        event.setOccurredAt(Instant.now());
        event.setPolicy(policy);
        return event;
    }

    @Data
    public static class PolicyPayload implements Serializable {
        private Long id;
        private String policyNo;
        private String holderName;
        private String productType;
        private BigDecimal premium;
        private String status;
    }
}
