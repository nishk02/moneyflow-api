package com.moneyflow.goal;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;
import java.time.LocalDate;

public record CreateGoalRequest(
        @NotBlank(message = "Goal name is required")
        String name,

        @NotNull(message = "Target amount is required")
        @DecimalMin(value = "1.0", message = "Target amount must be at least ₹1")
        BigDecimal targetAmount,

        @NotBlank(message = "Account is required")
        String accountId,

        @NotNull(message = "Start date is required")
        LocalDate startDate,

        @NotNull(message = "End date is required")
        LocalDate endDate
) {
}
