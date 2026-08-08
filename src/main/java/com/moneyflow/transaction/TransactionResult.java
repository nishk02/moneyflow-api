package com.moneyflow.transaction;

public record TransactionResult(
        TransactionResponse response,
        String warning
) {
}
