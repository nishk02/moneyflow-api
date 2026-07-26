package com.moneyflow.transaction;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;
import java.time.LocalDate;

public record CreateTransactionRequest(
        @NotNull(message = "Transaction date is required")
        LocalDate date,

        @NotNull(message = "Transaction type is required")
        TransactionType type,

        @NotBlank(message = "Category is required")
        String categoryId,

        @NotBlank(message = "From account is required")
        String accountId,

        String toAccountId,
        String toGoalId,

        @NotNull(message = "Amount is required")
        @DecimalMin(value = "0.01", message = "Amount must be greater than zero")
        BigDecimal amount,

        String notes
) {
}
