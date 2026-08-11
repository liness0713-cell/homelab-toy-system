package com.toysystem.policy.service;

import com.toysystem.policy.dto.CreatePolicyRequest;
import com.toysystem.policy.dto.UpdatePolicyRequest;
import com.toysystem.event.PolicyEventType;
import com.toysystem.policy.event.EventPublisher;
import com.toysystem.policy.event.PolicyEventFactory;
import com.toysystem.policy.exception.PolicyNotFoundException;
import com.toysystem.policy.mapper.PolicyMapper;
import com.toysystem.policy.model.Policy;
import com.toysystem.policy.model.PolicyStatus;
import com.toysystem.policy.sharding.ShardKeyUtil;
import com.toysystem.policy.sharding.ShardTableReader;
import com.toysystem.policy.sharding.SnowflakeIdGenerator;
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
    private final SnowflakeIdGenerator idGenerator;
    private final ShardTableReader shardTableReader;

    public Policy create(CreatePolicyRequest request) {
        LocalDateTime now = LocalDateTime.now();
        Policy policy = new Policy();
        // 分片号必须先算出来，再用来生成id——ShardingSphere会用同一个holder_name、
        // 同一个公式独立算一遍决定这一行实际插到哪张物理表，两边必须对得上（见ShardKeyUtil注释）。
        int shardIndex = ShardKeyUtil.shardIndexFor(request.getHolderName());
        policy.setId(idGenerator.nextId(shardIndex));
        policy.setPolicyNo(generatePolicyNo());
        policy.setHolderName(request.getHolderName());
        policy.setProductType(request.getProductType());
        policy.setPremium(request.getPremium());
        policy.setStatus(PolicyStatus.DRAFT);
        policy.setCreatedAt(now);
        policy.setUpdatedAt(now);

        policyMapper.insert(policy);
        eventPublisher.publish(PolicyEventFactory.of(PolicyEventType.POLICY_CREATED, policy));
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

    /**
     * P4.5对比路径：从id直接解码分片号，绕开ShardingSphere，用一个独立的原生JDBC连接
     * （见ShardTableReader/rawMySqlDataSource）直接查解出来的那一张物理表——单分片命中，
     * 不广播。特意不接Redis缓存，保证每次调用都是真的打到数据库，方便跟 getById(id) 的
     * 广播行为做对比（配合sql-show日志看）。
     */
    public Policy getByIdShardAware(Long id) {
        int shardIndex = SnowflakeIdGenerator.extractShard(id);
        Policy policy = shardTableReader.findByIdInShard(id, shardIndex);
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

        existing.setProductType(request.getProductType());
        existing.setPremium(request.getPremium());
        existing.setUpdatedAt(LocalDateTime.now());

        policyMapper.update(existing);
        eventPublisher.publish(PolicyEventFactory.of(PolicyEventType.POLICY_UPDATED, existing));
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
        eventPublisher.publish(PolicyEventFactory.of(PolicyEventType.POLICY_CANCELLED, existing));
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
