package com.moneyflow.transaction;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;

import java.math.BigDecimal;

public record GoalAllocationItem(
        @NotBlank(message = "Goal id is required")
        String goalId,

        @DecimalMin(value = "0.0", inclusive = false, message = "Allocation amount must be greater than zero")
        BigDecimal amount
) {
}
