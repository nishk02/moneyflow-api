package com.moneyflow.account;


import java.math.BigDecimal;

public record AccountResponse(
        String id,
        String name,
        String type,
        BigDecimal currentBalance,
        String currency,
        String colorLabel,
        int displayOrder,
        boolean goalLinked
) {
    public static AccountResponse from(Account account, boolean goalLinked) {
        return new AccountResponse(
                account.getId(),
                account.getName(),
                account.getType(),
                account.getCurrentBalance(),
                account.getCurrency(),
                account.getColorLabel(),
                account.getDisplayOrder(),
                goalLinked
        );
    }
}