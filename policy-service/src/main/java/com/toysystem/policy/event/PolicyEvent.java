package com.toysystem.policy.event;

import com.toysystem.policy.model.Policy;
import lombok.Data;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * policy-events topic 的消息体，字段/命名严格对齐 docs/kafka-event-schema.md，
 * 下游消费者（notification-service、后续的search-service）都按这个契约解析。
 */
@Data
public class PolicyEvent {

    private String eventId;
    private PolicyEventType eventType;
    private Instant occurredAt;
    private PolicyPayload policy;

    public static PolicyEvent of(PolicyEventType eventType, Policy policy) {
        PolicyEvent event = new PolicyEvent();
        event.setEventId(UUID.randomUUID().toString());
        event.setEventType(eventType);
        event.setOccurredAt(Instant.now());
        event.setPolicy(PolicyPayload.from(policy));
        return event;
    }

    @Data
    public static class PolicyPayload {
        private Long id;
        private String policyNo;
        private String holderName;
        private String productType;
        private BigDecimal premium;
        private String status;

        public static PolicyPayload from(Policy policy) {
            PolicyPayload payload = new PolicyPayload();
            payload.setId(policy.getId());
            payload.setPolicyNo(policy.getPolicyNo());
            payload.setHolderName(policy.getHolderName());
            payload.setProductType(policy.getProductType().name());
            payload.setPremium(policy.getPremium());
            payload.setStatus(policy.getStatus().name());
            return payload;
        }
    }
}
