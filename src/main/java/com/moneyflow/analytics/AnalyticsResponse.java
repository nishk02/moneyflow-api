package com.moneyflow.analytics;

import java.math.BigDecimal;
import java.time.LocalDate;

public record AnalyticsResponse(
        Period period,
        BigDecimal income,
        BigDecimal expense,
        BigDecimal savings,
        BigDecimal savingsRate,
        BigDecimal debtRatio
) {
    public record Period(LocalDate from, LocalDate to, String mode) {
    }
}
