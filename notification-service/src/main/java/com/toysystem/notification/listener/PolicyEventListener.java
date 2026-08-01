package com.toysystem.notification.listener;

import com.toysystem.notification.event.PolicyEvent;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

@Component
@Slf4j
public class PolicyEventListener {

    @KafkaListener(topics = "policy-events", groupId = "notification-service")
    public void onPolicyEvent(PolicyEvent event) {
        PolicyEvent.PolicyPayload policy = event.getPolicy();
        String message = switch (event.getEventType()) {
            case "POLICY_CREATED" -> "您的保单 %s 已创建成功，产品：%s，保费：%s".formatted(
                    policy.getPolicyNo(), policy.getProductType(), policy.getPremium());
            case "POLICY_UPDATED" -> "您的保单 %s 信息已更新".formatted(policy.getPolicyNo());
            case "POLICY_CANCELLED" -> "您的保单 %s 已取消".formatted(policy.getPolicyNo());
            default -> "保单 %s 发生未知事件: %s".formatted(policy.getPolicyNo(), event.getEventType());
        };

        // P3阶段的"模拟通知"就是打一条日志，不真的发邮件/短信
        log.info("[通知] eventId={} holder={} -> {}", event.getEventId(), policy.getHolderName(), message);
    }
}
