package com.toysystem.notification.event;

import lombok.Data;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * policy-events topic 消息体的消费端拷贝，字段严格对齐 docs/kafka-event-schema.md。
 * 按照本项目"每个服务独立pom，不共享代码模块"的约定，这里和 policy-service 里的
 * PolicyEvent 是两份独立维护但共享同一份契约的DTO。
 */
@Data
public class PolicyEvent {

    private String eventId;
    private String eventType;
    private Instant occurredAt;
    private PolicyPayload policy;

    @Data
    public static class PolicyPayload {
        private Long id;
        private String policyNo;
        private String holderName;
        private String productType;
        private BigDecimal premium;
        private String status;
    }
}
