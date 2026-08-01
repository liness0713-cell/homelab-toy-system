package com.toysystem.notification.listener;

import com.toysystem.event.PolicyEvent;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

@Component
@Slf4j
public class PolicyEventListener {

    @KafkaListener(topics = "policy-events", groupId = "notification-service")
    public void onPolicyEvent(PolicyEvent event) {
        PolicyEvent.PolicyPayload policy = event.getPolicy();
        // eventType现在是共享模块里的枚举（不再是裸字符串），switch覆盖了全部枚举值，
        // 编译器保证：以后event-contracts里加了新的事件类型，这里不改就编译不过。
        String message = switch (event.getEventType()) {
            case POLICY_CREATED -> "您的保单 %s 已创建成功，产品：%s，保费：%s".formatted(
                    policy.getPolicyNo(), policy.getProductType(), policy.getPremium());
            case POLICY_UPDATED -> "您的保单 %s 信息已更新".formatted(policy.getPolicyNo());
            case POLICY_CANCELLED -> "您的保单 %s 已取消".formatted(policy.getPolicyNo());
        };

        // P3阶段的"模拟通知"就是打一条日志，不真的发邮件/短信
        log.info("[通知] eventId={} holder={} -> {}", event.getEventId(), policy.getHolderName(), message);
    }
}
