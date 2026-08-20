package com.moneyflow.transaction;

import java.util.List;

public enum FlowType {
    INCOME(List.of(TransactionType.INCOME)),
    EXPENSE(List.of(
            TransactionType.FIXED_EXPENSE,
            TransactionType.VARIABLE_EXPENSE,
            TransactionType.LENDING,
            TransactionType.BORROWING,
            TransactionType.REPAYMENT
    ));

    private final List<TransactionType> types;

    FlowType(List<TransactionType> types) {
        this.types = types;
    }

    public List<TransactionType> getTypes() {
        return types;
    }
}