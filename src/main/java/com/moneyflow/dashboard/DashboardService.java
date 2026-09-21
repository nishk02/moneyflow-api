package com.moneyflow.dashboard;

import com.moneyflow.account.Account;
import com.moneyflow.account.AccountRepository;
import com.moneyflow.account.AccountResponse;
import com.moneyflow.auth.User;
import com.moneyflow.auth.UserRepository;
import com.moneyflow.category.Category;
import com.moneyflow.goal.Goal;
import com.moneyflow.goal.GoalRepository;
import com.moneyflow.shared.exception.ApiException;
import com.moneyflow.transaction.*;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class DashboardService {
    private final UserRepository userRepository;
    private final AccountRepository accountRepository;
    private final TransactionRepository transactionRepository;
    private final GoalRepository goalRepository;
    private final GoalAllocationService goalAllocationService;

    private static final List<String> MOTIVATIONAL_QUOTES = List.of(
            "Savings is a Habit. Keeping track is the Key.",
            "You can make money two ways, make more or spend less.",
            "A budget is telling your money where to go.",
            "Do not save what is left after spending; spend what is left after saving.",
            "Financial freedom is available to those who learn about it and work for it."
    );
    private static final BigDecimal NEGLIGIBLE_REMAINDER = new BigDecimal("1.00");

    @Transactional(readOnly = true)
    public DashboardResponse getDashboard(String userId) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> ApiException.notFound("User not found"));

        // Goals
        List<Goal> activeGoals = goalRepository.findByUserIdAndActiveTrueOrderByDisplayOrderAsc(userId);
        Set<String> goalLinkedAccountIds = activeGoals.stream()
                .filter(g -> !"COMPLETED".equals(g.getStatus()))
                .map(g -> g.getAccount().getId())
                .collect(Collectors.toSet());

        // Accounts
        List<Account> accounts = accountRepository.findByUserIdAndActiveTrue(userId);

        List<AccountResponse> accountResponses = accounts.stream()
                .map(a -> AccountResponse.from(a, goalLinkedAccountIds.contains(a.getId())))
                .toList();

        BigDecimal totalBalance = accounts.stream()
                .map(Account::getCurrentBalance)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        BigDecimal monthlyTarget = activeGoals.stream()
                .filter(g -> !"COMPLETED".equals(g.getStatus()))
                .map(g -> g.getMonthlySavingsRequired() != null ? g.getMonthlySavingsRequired() : BigDecimal.ZERO)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        // Saved this month
        LocalDate now = LocalDate.now();
        BigDecimal grossSavedThisMonth = Optional.ofNullable(transactionRepository
                .sumTransferToGoalByCalendarMonth(
                        userId,
                        now.getYear(),
                        now.getMonthValue())).orElse(BigDecimal.ZERO);

        BigDecimal withdrawalsThisMonth = goalAllocationService
                .sumWithdrawalsByCalendarMonth(userId, now.getYear(), now.getMonthValue());

        BigDecimal savedThisMonth = grossSavedThisMonth.subtract(withdrawalsThisMonth);

        // Balance percentage
        BigDecimal totalIncomeThisMonth = transactionRepository
                .sumByUserIdAndTypesAndMonth(
                        userId,
                        List.of(TransactionType.INCOME),
                        now.getYear(),
                        now.getMonthValue());

        BigDecimal openingBalanceThisMonth = transactionRepository
                .sumByUserIdAndTypesAndCategoryAndMonth(
                        userId,
                        List.of(TransactionType.SETTLEMENT),
                        Category.OPENING_BALANCE_CATEGORY_ID,
                        now.getYear(),
                        now.getMonthValue());

        BigDecimal balancePercentageBase = totalIncomeThisMonth.add(openingBalanceThisMonth);

        BigDecimal balancePercentage = balancePercentageBase.compareTo(BigDecimal.ZERO) > 0
                ? totalBalance.multiply(new BigDecimal("100"))
                .divide(balancePercentageBase, 1, RoundingMode.HALF_UP)
                : null;

        // Savings message
        String savingsMessage = buildSavingsMessage(savedThisMonth, monthlyTarget, totalBalance);

        // Last entries
        List<Transaction> recentTransactions = transactionRepository
                .findTop5ByUserIdOrderByDateDescCreatedAtDesc(userId);

        Map<String, List<TransactionGoalAllocation>> allocationsByTransactionId = goalAllocationService
                .getAllocationEntitiesByTransactionIds(
                        recentTransactions.stream().map(Transaction::getId).toList());

        List<TransactionResponse> lastEntries = recentTransactions.stream()
                .map(t -> TransactionResponse.from(t, allocationsByTransactionId.getOrDefault(t.getId(), List.of())))
                .toList();

        // Onboarding checklist
        boolean accountsAdded = !accounts.isEmpty();
        boolean goalsAdded = !activeGoals.isEmpty();

        DashboardResponse.OnboardingChecklist checklist = new DashboardResponse.OnboardingChecklist(
                accountsAdded,
                false, // PlannedAmounts deferred to Phase 2
                goalsAdded);

        // Motivational quote
        String quote = MOTIVATIONAL_QUOTES.get(
                (int) (Math.abs(userId.hashCode())
                        % MOTIVATIONAL_QUOTES.size()));

        // Assemble response
        DashboardResponse.UserSummary userSummary =
                new DashboardResponse.UserSummary(
                        user.getFirstName(),
                        user.getOnboardingStep(),
                        quote);

        DashboardResponse.BalanceSummary balanceSummary =
                new DashboardResponse.BalanceSummary(
                        totalBalance,
                        balancePercentage,
                        monthlyTarget,
                        savedThisMonth,
                        savingsMessage);

        return new DashboardResponse(
                userSummary,
                balanceSummary,
                accountResponses,
                lastEntries,
                checklist);
    }

    private String buildSavingsMessage(BigDecimal savedThisMonth, BigDecimal monthlyTarget, BigDecimal totalBalance) {
        // No goals set
        if (monthlyTarget.compareTo(BigDecimal.ZERO) == 0) {
            return "Set a savings goal to get started!";
        }

        BigDecimal remaining = monthlyTarget.subtract(savedThisMonth);

        // Target reached, or close enough that the gap is rounding dust, not a
        // meaningful amount to ask someone to go save today (e.g. a few paise off
        // after netting real transactions).
        if (remaining.compareTo(NEGLIGIBLE_REMAINDER) < 0) {
            return "🎯 You are all set! Just hold on to it 💰";
        }

        // Balance is less than what's needed — encourage partial saving
        if (totalBalance.compareTo(remaining) < 0) {
            return "Save what you can — every ₹"
                    + totalBalance.setScale(0, RoundingMode.FLOOR)
                    + " counts towards your goal! 💪";
        }

        // Balance covers the remaining target — actionable message.
        // CEILING, not FLOOR: rounding down here would understate what's actually
        // needed to hit the exact target (e.g. ₹5000.60 remaining → "save ₹5000"
        // leaves the target 60 paise short even if followed exactly).
        return "You can save ₹" + remaining.setScale(0, RoundingMode.CEILING) + " today!";
    }
}
