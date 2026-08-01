package com.toysystem.policy.service;

import com.toysystem.policy.dto.CreatePolicyRequest;
import com.toysystem.policy.dto.UpdatePolicyRequest;
import com.toysystem.policy.event.EventPublisher;
import com.toysystem.policy.event.PolicyEvent;
import com.toysystem.policy.event.PolicyEventType;
import com.toysystem.policy.exception.PolicyNotFoundException;
import com.toysystem.policy.mapper.PolicyMapper;
import com.toysystem.policy.model.Policy;
import com.toysystem.policy.model.PolicyStatus;
import lombok.RequiredArgsConstructor;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.CachePut;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class PolicyService {

    private static final String CACHE_NAME = "policy-detail";

    private final PolicyMapper policyMapper;
    private final EventPublisher eventPublisher;

    public Policy create(CreatePolicyRequest request) {
        LocalDateTime now = LocalDateTime.now();
        Policy policy = new Policy();
        policy.setPolicyNo(generatePolicyNo());
        policy.setHolderName(request.getHolderName());
        policy.setProductType(request.getProductType());
        policy.setPremium(request.getPremium());
        policy.setStatus(PolicyStatus.DRAFT);
        policy.setCreatedAt(now);
        policy.setUpdatedAt(now);

        policyMapper.insert(policy);
        eventPublisher.publish(PolicyEvent.of(PolicyEventType.POLICY_CREATED, policy));
        return policy;
    }

    @Cacheable(cacheNames = CACHE_NAME, key = "#id")
    public Policy getById(Long id) {
        Policy policy = policyMapper.findById(id);
        if (policy == null) {
            throw new PolicyNotFoundException(id);
        }
        return policy;
    }

    public List<Policy> listAll() {
        return policyMapper.findAll();
    }

    @CachePut(cacheNames = CACHE_NAME, key = "#id")
    public Policy update(Long id, UpdatePolicyRequest request) {
        Policy existing = policyMapper.findById(id);
        if (existing == null) {
            throw new PolicyNotFoundException(id);
        }

        existing.setHolderName(request.getHolderName());
        existing.setProductType(request.getProductType());
        existing.setPremium(request.getPremium());
        existing.setUpdatedAt(LocalDateTime.now());

        policyMapper.update(existing);
        eventPublisher.publish(PolicyEvent.of(PolicyEventType.POLICY_UPDATED, existing));
        return existing;
    }

    @CachePut(cacheNames = CACHE_NAME, key = "#id")
    public Policy cancel(Long id) {
        Policy existing = policyMapper.findById(id);
        if (existing == null) {
            throw new PolicyNotFoundException(id);
        }

        existing.setStatus(PolicyStatus.CANCELLED);
        existing.setUpdatedAt(LocalDateTime.now());

        policyMapper.update(existing);
        eventPublisher.publish(PolicyEvent.of(PolicyEventType.POLICY_CANCELLED, existing));
        return existing;
    }

    @CacheEvict(cacheNames = CACHE_NAME, key = "#id")
    public void delete(Long id) {
        Policy existing = policyMapper.findById(id);
        if (existing == null) {
            throw new PolicyNotFoundException(id);
        }
        policyMapper.deleteById(id);
    }

    private String generatePolicyNo() {
        return "POL-" + UUID.randomUUID().toString().replace("-", "").substring(0, 12).toUpperCase();
    }
}
