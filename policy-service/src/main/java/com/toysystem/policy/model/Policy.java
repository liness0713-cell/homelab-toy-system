package com.toysystem.policy.model;

import lombok.Data;

import java.io.Serializable;
import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
public class Policy implements Serializable {
    private Long id;
    private String policyNo;
    private String holderName;
    private ProductType productType;
    private BigDecimal premium;
    private PolicyStatus status;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
