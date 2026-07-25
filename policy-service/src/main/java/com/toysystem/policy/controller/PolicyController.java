package com.toysystem.policy.controller;

import com.toysystem.policy.dto.CreatePolicyRequest;
import com.toysystem.policy.dto.UpdatePolicyRequest;
import com.toysystem.policy.model.Policy;
import com.toysystem.policy.service.PolicyService;
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

    @GetMapping("/{id}")
    public Policy getById(@PathVariable Long id) {
        return policyService.getById(id);
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
