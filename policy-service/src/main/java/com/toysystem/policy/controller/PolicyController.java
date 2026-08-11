package com.toysystem.policy.controller;

import com.toysystem.policy.dto.CreatePolicyRequest;
import com.toysystem.policy.dto.UpdatePolicyRequest;
import com.toysystem.policy.model.Policy;
import com.toysystem.policy.service.PolicyService;
import com.toysystem.policy.sharding.SnowflakeIdGenerator;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/policies")
@RequiredArgsConstructor
public class PolicyController {

    private final PolicyService policyService;

    @PostMapping
    public ResponseEntity<Policy> create(@Valid @RequestBody CreatePolicyRequest request) {
        Policy created = policyService.create(request);
        return ResponseEntity.status(HttpStatus.CREATED).body(created);
    }

    /**
     * P4.5对比开关：shardAware=false（默认）走原来的逻辑表查询，ShardingSphere广播查询
     * 全部4张物理表再合并；shardAware=true从id解码分片号，直接命中单张物理表，不广播。
     * 两条路径应该返回同一份数据，差异在"底层发了几条SQL"——配合policy-service日志里
     * ShardingSphere的sql-show输出对比，X-Policy-Shard-Route响应头标出这次实际打的是
     * 广播还是单分片、命中了哪张表。
     */
    @GetMapping("/{id}")
    public ResponseEntity<Policy> getById(
            @PathVariable Long id,
            @RequestParam(defaultValue = "false") boolean shardAware) {
        if (shardAware) {
            int shardIndex = SnowflakeIdGenerator.extractShard(id);
            Policy policy = policyService.getByIdShardAware(id);
            return ResponseEntity.ok()
                    .header("X-Policy-Shard-Route", "single:policy_" + shardIndex)
                    .body(policy);
        }
        Policy policy = policyService.getById(id);
        return ResponseEntity.ok()
                .header("X-Policy-Shard-Route", "broadcast:policy_0..policy_3")
                .body(policy);
    }

    @GetMapping
    public List<Policy> listAll() {
        return policyService.listAll();
    }

    @PutMapping("/{id}")
    public Policy update(@PathVariable Long id, @Valid @RequestBody UpdatePolicyRequest request) {
        return policyService.update(id, request);
    }

    @PostMapping("/{id}/cancel")
    public Policy cancel(@PathVariable Long id) {
        return policyService.cancel(id);
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable Long id) {
        policyService.delete(id);
        return ResponseEntity.noContent().build();
    }
}
