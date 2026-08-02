package com.moneyflow.goal;

import jakarta.validation.constraints.DecimalMin;

import java.math.BigDecimal;
import java.time.LocalDate;

public record UpdateGoalRequest(
        String name,

        @DecimalMin(value = "1.0", message = "Target amount cannot be negative")
        BigDecimal targetAmount,

        String accountId,

        LocalDate startDate,

        LocalDate endDate
) {
}
