package com.moneyflow.account;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Pattern;

import java.math.BigDecimal;

public record UpdateAccountRequest(
        String name,

        @Pattern(regexp = "CASH|BANK|WALLET", message = "Type must be CASH, BANK or WALLET")
        String type,

        @DecimalMin(value = "0.0", message = "Balance cannot be negative")
        BigDecimal currentBalance,

        String colorLabel
) {
}
