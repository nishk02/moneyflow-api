package com.moneyflow.account;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

import java.math.BigDecimal;

public record CreateAccountRequest(
        @NotBlank(message = "Account name is required")
        String name,

        @NotBlank(message = "Account type is required")
        @Pattern(regexp = "CASH|BANK|WALLET", message = "Type must be CASH, BANK, or WALLET")
        String type,

        @DecimalMin(value = "0.0", message = "Opening balance cannot be negative")
        BigDecimal currentBalance,

        String colorLabel
) {
}