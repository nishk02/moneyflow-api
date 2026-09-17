package com.moneyflow.transaction;

import java.math.BigDecimal;
import java.util.List;

public record InsufficientFreeBalanceDetails(
        BigDecimal freeBalance,
        BigDecimal shortfall,
        List<GoalAvailability> availableGoals
) {
    public record GoalAvailability(String goalId, String goalName, BigDecimal currentProgress) {
    }
}
