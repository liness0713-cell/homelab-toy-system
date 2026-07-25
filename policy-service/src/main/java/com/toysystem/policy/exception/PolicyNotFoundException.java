package com.toysystem.policy.exception;

public class PolicyNotFoundException extends RuntimeException {
    public PolicyNotFoundException(Long id) {
        super("Policy not found: id=" + id);
    }
}
