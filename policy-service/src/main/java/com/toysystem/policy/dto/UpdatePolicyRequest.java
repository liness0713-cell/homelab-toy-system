package com.toysystem.policy.dto;

import com.toysystem.policy.model.ProductType;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.math.BigDecimal;

@Data
public class UpdatePolicyRequest {

    @NotBlank
    private String holderName;

    @NotNull
    private ProductType productType;

    @NotNull
    @DecimalMin(value = "0.0", inclusive = false)
    private BigDecimal premium;
}
