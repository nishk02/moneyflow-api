package com.moneyflow.transaction;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

public record TransactionResponse(
        String id,
        LocalDate date,
        TransactionType type,
        CategorySummary category,
        AccountSummary account,
        AccountSummary toAccount,
        String toGoalId,
        BigDecimal amount,
        String displayAmount,
        String notes,
        String financialYear,
        int month,
        int calendarMonth,
        int calendarYear,
        boolean planned,
        String plannedAmountId,
        List<GoalAllocationSummary> goalAllocations
) {
    public record CategorySummary(String id, String name, String icon) {}
    public record AccountSummary(String id, String name, String colorLabel) {}
    public record GoalAllocationSummary(String goalId, String goalName, BigDecimal amount) {}

    public static TransactionResponse from(Transaction transaction, List<TransactionGoalAllocation> allocations) {
        CategorySummary category = transaction.getCategory() != null
                ? new CategorySummary(
                        transaction.getCategory().getId(),
                        transaction.getCategory().getName(),
                        transaction.getCategory().getIcon())
                : null;

        AccountSummary account = new AccountSummary(
                transaction.getAccount().getId(),
                transaction.getAccount().getName(),
                transaction.getAccount().getColorLabel());

        AccountSummary toAccount = transaction.getToAccount() != null
                ? new AccountSummary(
                        transaction.getToAccount().getId(),
                        transaction.getToAccount().getName(),
                        transaction.getToAccount().getColorLabel())
                : null;

        String displayAmount = buildDisplayAmount(transaction.getType(), transaction.getAmount());

        List<GoalAllocationSummary> goalAllocations = allocations.stream()
                .map(a -> new GoalAllocationSummary(a.getGoal().getId(), a.getGoal().getName(), a.getAmount()))
                .toList();

        return new TransactionResponse(
                transaction.getId(),
                transaction.getDate(),
                transaction.getType(),
                category,
                account,
                toAccount,
                transaction.getToGoalId(),
                transaction.getAmount(),
                displayAmount,
                transaction.getNotes(),
                transaction.getFinancialYear(),
                transaction.getMonth(),
                transaction.getCalendarMonth(),
                transaction.getCalendarYear(),
                transaction.isPlanned(),
                transaction.getPlannedAmountId(),
                goalAllocations
        );
    }

    private static String buildDisplayAmount(TransactionType type, BigDecimal amount) {
        return switch (type) {
            case INCOME, SETTLEMENT -> "+₹" + amount;
            case TRANSFER -> "₹" + amount;
            default -> "-₹" + amount;
        };
    }
}
