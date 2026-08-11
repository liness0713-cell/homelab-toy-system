package com.toysystem.policy.dto;

import com.toysystem.policy.model.ProductType;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.math.BigDecimal;

@Data
public class UpdatePolicyRequest {

    // holderName不在这里：它是P4.5引入的分片键，创建后不可变（改它意味着这一行要"搬"到
    // 另一张物理表，UPDATE做不到，ShardingSphere会直接拒绝）。

    @NotNull
    private ProductType productType;

    @NotNull
    @DecimalMin(value = "0.0", inclusive = false)
    private BigDecimal premium;
}
