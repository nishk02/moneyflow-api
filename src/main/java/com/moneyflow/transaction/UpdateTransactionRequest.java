package com.moneyflow.transaction;

import jakarta.validation.constraints.DecimalMin;

import java.math.BigDecimal;
import java.time.LocalDate;

public record UpdateTransactionRequest(
        LocalDate date,

        String categoryId,

        @DecimalMin(value = "0.01", message = "Amount must be greater than zero")
        BigDecimal amount,

        String notes
) {
}
