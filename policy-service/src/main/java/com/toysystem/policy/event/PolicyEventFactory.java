package com.toysystem.policy.event;

import com.toysystem.event.PolicyEvent;
import com.toysystem.event.PolicyEventType;
import com.toysystem.policy.model.Policy;

/**
 * 把内部实体 Policy 转成共享契约 PolicyEvent。这层转换必须留在 policy-service 里——
 * event-contracts 是纯契约模块，不能反过来依赖具体业务服务的实体类。
 */
public final class PolicyEventFactory {

    private PolicyEventFactory() {
    }

    public static PolicyEvent of(PolicyEventType eventType, Policy policy) {
        PolicyEvent.PolicyPayload payload = new PolicyEvent.PolicyPayload();
        payload.setId(policy.getId());
        payload.setPolicyNo(policy.getPolicyNo());
        payload.setHolderName(policy.getHolderName());
        payload.setProductType(policy.getProductType().name());
        payload.setPremium(policy.getPremium());
        payload.setStatus(policy.getStatus().name());

        return PolicyEvent.of(eventType, payload);
    }
}
