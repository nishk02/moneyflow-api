package com.moneyflow.goal;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;

public record GoalResponse(
        String id,
        String name,
        BigDecimal targetAmount,
        AccountSummary account,
        LocalDate startDate,
        LocalDate endDate,
        BigDecimal currentProgress,
        BigDecimal progressPercentage,
        BigDecimal plannedMonthlySavings,
        BigDecimal currentMonthlySavingsRequired,
        long monthsRemaining,
        String status,
        boolean isUpcoming,
        int displayOrder
) {
    public record AccountSummary(String id, String name, String colorLabel) {}

    public static GoalResponse from(Goal goal) {
        AccountSummary account = new AccountSummary(
                goal.getAccount().getId(),
                goal.getAccount().getName(),
                goal.getAccount().getColorLabel());

        final BigDecimal progressPercentage = calculateProgressPercentage(goal);

        long monthsRemaining = goal.getEndDate() != null
                ? Math.max(0, monthsBetweenIgnoringDay(LocalDate.now(), goal.getEndDate()))
                : 0;

        boolean isUpcoming = goal.getStartDate() != null && goal.getStartDate().isAfter(LocalDate.now());

        return new GoalResponse(
                goal.getId(),
                goal.getName(),
                goal.getTargetAmount(),
                account,
                goal.getStartDate(),
                goal.getEndDate(),
                goal.getCurrentProgress(),
                progressPercentage,
                goal.getMonthlySavingsRequired(), // frozen plan, set at creation/re-plan
                calculateCurrentMonthlySavingsRequired(goal), // live, recomputed from today
                monthsRemaining,
                goal.getStatus(),
                isUpcoming,
                goal.getDisplayOrder()
        );
    }

    private static BigDecimal calculateProgressPercentage(Goal goal) {
        if (goal == null
                || goal.getTargetAmount() == null
                || goal.getCurrentProgress() == null
                || goal.getTargetAmount().compareTo(BigDecimal.ZERO) <= 0) {
            return BigDecimal.ZERO;
        }

        return goal.getCurrentProgress()
                .multiply(new BigDecimal("100"))
                .divide(goal.getTargetAmount(), 1, RoundingMode.HALF_UP);
    }

    private static BigDecimal calculateCurrentMonthlySavingsRequired(Goal goal) {
        if (goal.getTargetAmount() == null || goal.getEndDate() == null) {
            return BigDecimal.ZERO;
        }

        BigDecimal currentProgress = goal.getCurrentProgress() != null ? goal.getCurrentProgress() : BigDecimal.ZERO;
        BigDecimal remainingAmount = goal.getTargetAmount().subtract(currentProgress);

        if (remainingAmount.compareTo(BigDecimal.ZERO) <= 0) {
            return BigDecimal.ZERO;
        }

        long monthsRemaining = Math.max(0, monthsBetweenIgnoringDay(LocalDate.now(), goal.getEndDate()));

        if (monthsRemaining <= 0) {
            return remainingAmount;
        }

        return remainingAmount.divide(BigDecimal.valueOf(monthsRemaining), 1, RoundingMode.HALF_UP);
    }

    /** Months between two dates, ignoring the day-of-month component. */
    private static long monthsBetweenIgnoringDay(LocalDate start, LocalDate end) {
        return ChronoUnit.MONTHS.between(start.withDayOfMonth(1), end.withDayOfMonth(1));
    }
}
