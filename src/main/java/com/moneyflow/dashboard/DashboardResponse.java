package com.moneyflow.dashboard;

import com.moneyflow.account.AccountResponse;
import com.moneyflow.transaction.TransactionResponse;

import java.math.BigDecimal;
import java.util.List;

public record DashboardResponse(
        UserSummary user,
        BalanceSummary balance,
        List<AccountResponse> accounts,
        List<TransactionResponse> lastEntries,
        OnboardingChecklist onboardingChecklist
) {
    public record UserSummary(
            String firstName,
            int onboardingStep,
            String motivationalQuote
    ) {}

    public record BalanceSummary(
            BigDecimal totalAvailableBalance,
            BigDecimal balancePercentage,
            BigDecimal monthlyTarget,
            BigDecimal savedThisMonth,
            String savingsMessage
    ) {}

    public record OnboardingChecklist(
            boolean accountsAdded,
            boolean plannedAmountsAdded,
            boolean goalsAdded
    ) {}
}
