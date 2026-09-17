package com.moneyflow.transaction;

import java.math.BigDecimal;

public record GoalAllocationItem(String goalId, BigDecimal amount) {
}
