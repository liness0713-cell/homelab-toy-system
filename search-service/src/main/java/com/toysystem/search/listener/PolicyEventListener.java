package com.toysystem.search.listener;

import com.toysystem.event.PolicyEvent;
import com.toysystem.search.document.PolicyDocument;
import com.toysystem.search.repository.PolicyDocumentRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

/**
 * policy-events 的消费者B（跟 notification-service 是完全独立的consumer group）。
 * 收到事件后把最新的保单快照整份写入ES，供只读搜索API用——这是CQRS的读路径。
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class PolicyEventListener {

    private final PolicyDocumentRepository repository;

    @KafkaListener(topics = "policy-events", groupId = "search-service")
    public void onPolicyEvent(PolicyEvent event) {
        PolicyEvent.PolicyPayload policy = event.getPolicy();

        PolicyDocument document = new PolicyDocument();
        document.setId(policy.getPolicyNo());
        document.setPolicyNo(policy.getPolicyNo());
        document.setHolderName(policy.getHolderName());
        document.setProductType(policy.getProductType());
        document.setPremium(policy.getPremium());
        document.setStatus(policy.getStatus());

        repository.save(document);
        log.info("[search-index] {} policyNo={} 已写入ES", event.getEventType(), policy.getPolicyNo());
    }
}
