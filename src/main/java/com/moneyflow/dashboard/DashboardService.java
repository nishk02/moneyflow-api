package com.moneyflow.dashboard;

import com.moneyflow.account.Account;
import com.moneyflow.account.AccountRepository;
import com.moneyflow.account.AccountResponse;
import com.moneyflow.auth.User;
import com.moneyflow.auth.UserRepository;
import com.moneyflow.goal.Goal;
import com.moneyflow.goal.GoalRepository;
import com.moneyflow.shared.exception.ApiException;
import com.moneyflow.transaction.TransactionRepository;
import com.moneyflow.transaction.TransactionResponse;
import com.moneyflow.transaction.TransactionType;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

@Service
@RequiredArgsConstructor
public class DashboardService {
    private final UserRepository userRepository;
    private final AccountRepository accountRepository;
    private final TransactionRepository transactionRepository;
    private final GoalRepository goalRepository;

    private static final List<String> MOTIVATIONAL_QUOTES = List.of(
            "Savings is a Habit. Keeping track is the Key.",
            "You can make money two ways, make more or spend less.",
            "A budget is telling your money where to go.",
            "Do not save what is left after spending; spend what is left after saving.",
            "Financial freedom is available to those who learn about it and work for it."
    );

    @Transactional(readOnly = true)
    public DashboardResponse getDashboard(String userId) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> ApiException.notFound("User not found"));

        // Accounts
        List<Account> accounts = accountRepository.findByUserIdAndActiveTrue(userId);

        List<AccountResponse> accountResponses = accounts.stream()
                .map(AccountResponse::from)
                .toList();

        BigDecimal totalBalance = accounts.stream()
                .map(Account::getCurrentBalance)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        // Goals
        List<Goal> activeGoals = goalRepository.findByUserIdAndActiveTrueOrderByDisplayOrderAsc(userId);

        BigDecimal monthlyTarget = activeGoals.stream()
                .filter(g -> !"COMPLETED".equals(g.getStatus()))
                .map(g -> g.getMonthlySavingsRequired() != null ? g.getMonthlySavingsRequired() : BigDecimal.ZERO)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        // Saved this month
        LocalDate now = LocalDate.now();
        BigDecimal savedThisMonth = Optional.ofNullable(transactionRepository
                .sumByUserIdAndTypesAndMonth(
                        userId,
                        List.of(TransactionType.TRANSFER),
                        now.getYear(),
                        now.getMonthValue())).orElse(BigDecimal.ZERO);

        // Balance percentage
        BigDecimal totalIncomeThisMonth = transactionRepository
                .sumByUserIdAndTypesAndMonth(
                        userId,
                        List.of(TransactionType.INCOME),
                        now.getYear(),
                        now.getMonthValue());

        BigDecimal balancePercentage = totalIncomeThisMonth
                .compareTo(BigDecimal.ZERO) > 0
                ? totalBalance
                    .multiply(new BigDecimal("100"))
                    .divide(totalIncomeThisMonth, 1, RoundingMode.HALF_UP)
                : BigDecimal.ZERO;

        // Savings message
        String savingsMessage = buildSavingsMessage(savedThisMonth, monthlyTarget, totalBalance);

        // Last entries
        List<TransactionResponse> lastEntries = transactionRepository
                .findByUserIdOrderByDateDescCreatedAtDesc(userId)
                .stream()
                .limit(5)
                .map(TransactionResponse::from)
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

        // Already hit the target this month
        if (savedThisMonth.compareTo(monthlyTarget) >= 0) {
            return "🎯 You are all set! Just hold on to it 💰";
        }

        BigDecimal remaining = monthlyTarget.subtract(savedThisMonth);

        // Balance is less than what's needed — encourage partial saving
        if (totalBalance.compareTo(remaining) < 0) {
            return "Save what you can — every ₹"
                    + totalBalance.setScale(0, RoundingMode.FLOOR)
                    + " counts towards your goal! 💪";
        }

        // Balance covers the remaining target — actionable message
        return "You can save ₹" + remaining.setScale(0, RoundingMode.FLOOR) + " today!";
    }
}
