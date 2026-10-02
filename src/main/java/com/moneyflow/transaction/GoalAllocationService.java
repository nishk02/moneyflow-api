package com.moneyflow.transaction;

import com.moneyflow.account.Account;
import com.moneyflow.goal.Goal;
import com.moneyflow.goal.GoalRepository;
import com.moneyflow.shared.exception.ApiErrorCodes;
import com.moneyflow.shared.exception.ApiException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class GoalAllocationService {
    private final GoalRepository goalRepository;
    private final TransactionGoalAllocationRepository allocationRepository;

    public List<Goal> activeGoalsOn(Account account) {
        return goalRepository.findByAccountIdAndActiveTrueAndStatusNotOrderByDisplayOrderAsc(
                account.getId(), "COMPLETED");
    }

    public List<GoalAllocationItem> getExistingAllocations(Transaction transaction) {
        return allocationRepository.findByTransactionId(transaction.getId()).stream()
                .map(a -> new GoalAllocationItem(a.getGoal().getId(), a.getAmount()))
                .toList();
    }

    public List<TransactionGoalAllocation> getAllocationEntities(Transaction transaction) {
        return allocationRepository.findByTransactionId(transaction.getId());
    }

    public Map<String, List<TransactionGoalAllocation>> getAllocationEntitiesByTransactionIds(List<String> transactionIds) {
        if (transactionIds.isEmpty()) return Map.of();
        return allocationRepository.findByTransactionIdIn(transactionIds).stream()
                .collect(Collectors.groupingBy(a -> a.getTransaction().getId()));
    }

    public BigDecimal getFreeBalance(Account account) {
        BigDecimal earmarked = activeGoalsOn(account).stream()
                .map(Goal::getCurrentProgress)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        return account.getCurrentBalance().subtract(earmarked);
    }

    @Transactional
    public void applyAllocations(
            Transaction transaction, List<GoalAllocationItem> allocations, GoalAllocationDirection direction) {
        if (allocations == null || allocations.isEmpty()) return;

        long distinctGoalCount = allocations.stream().map(GoalAllocationItem::goalId).distinct().count();
        if (distinctGoalCount != allocations.size()) {
            throw ApiException.badRequest("Each goal can only appear once in a single allocation list.");
        }

        String userId = transaction.getUser().getId();
        String accountId = transaction.getAccount().getId();

        for (GoalAllocationItem item : allocations) {
            Goal goal = goalRepository.findByIdAndUserId(item.goalId(), userId)
                    .orElseThrow(() -> ApiException.notFound("Goal not found"));

            if (!goal.getAccount().getId().equals(accountId)) {
                throw ApiException.badRequest(
                        "Goal '" + goal.getName() + "' is not linked to this account");
            }
            if (!goal.isActive() || "COMPLETED".equals(goal.getStatus())) {
                throw ApiException.badRequest(
                        "Goal '" + goal.getName() + "' is not active");
            }
            if (direction == GoalAllocationDirection.DECREASE
                    && item.amount().compareTo(goal.getCurrentProgress()) > 0) {
                throw ApiException.badRequest(
                        "Cannot allocate ₹" + formatAmount(item.amount()) + " from goal '" + goal.getName() +
                                "' — only ₹" + formatAmount(goal.getCurrentProgress()) + " is currently earmarked there.");
            }
            if (direction == GoalAllocationDirection.INCREASE
                    && goal.getCurrentProgress().add(item.amount()).compareTo(goal.getTargetAmount()) > 0) {
                BigDecimal headroom = goal.getTargetAmount().subtract(goal.getCurrentProgress());
                throw ApiException.badRequest(
                        "Cannot allocate ₹" + formatAmount(item.amount()) + " to goal '" + goal.getName() +
                                "' — only ₹" + formatAmount(headroom) + " is left to reach its target.");
            }

            goal.setCurrentProgress(direction == GoalAllocationDirection.DECREASE
                    ? goal.getCurrentProgress().subtract(item.amount())
                    : goal.getCurrentProgress().add(item.amount()));
            goalRepository.save(goal);

            TransactionGoalAllocation allocation = new TransactionGoalAllocation();
            allocation.setTransaction(transaction);
            allocation.setGoal(goal);
            allocation.setAmount(item.amount());
            allocationRepository.save(allocation);
        }
    }

    @Transactional
    public void reverseAllocations(Transaction transaction, GoalAllocationDirection originalDirection) {
        List<TransactionGoalAllocation> existing = allocationRepository.findByTransactionId(transaction.getId());
        if (existing.isEmpty()) return;

        for (TransactionGoalAllocation allocation : existing) {
            Goal goal = allocation.getGoal();
            goal.setCurrentProgress(originalDirection == GoalAllocationDirection.DECREASE
                    ? goal.getCurrentProgress().add(allocation.getAmount())
                    : goal.getCurrentProgress().subtract(allocation.getAmount()));
            goalRepository.save(goal);
        }

        allocationRepository.deleteAll(existing);
    }

    public InsufficientFreeBalanceDetails buildInsufficientFreeBalanceDetails(Account account, BigDecimal requiredAmount) {
        BigDecimal freeBalance = getFreeBalance(account);
        BigDecimal shortfall = requiredAmount.subtract(freeBalance);

        List<InsufficientFreeBalanceDetails.GoalAvailability> availableGoals = activeGoalsOn(account).stream()
                .map(g -> new InsufficientFreeBalanceDetails.GoalAvailability(
                        g.getId(), g.getName(), g.getCurrentProgress()))
                .toList();

        return new InsufficientFreeBalanceDetails(freeBalance, shortfall, availableGoals);
    }

    public InsufficientFreeBalanceDetails buildBalanceCorrectionDetails(Account account, BigDecimal newBalance) {
        BigDecimal freeBalance = getFreeBalance(account);

        List<Goal> goals = activeGoalsOn(account);
        BigDecimal earmarked = goals.stream()
                .map(Goal::getCurrentProgress)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal shortfall = earmarked.subtract(newBalance);

        List<InsufficientFreeBalanceDetails.GoalAvailability> availableGoals = goals.stream()
                .map(g -> new InsufficientFreeBalanceDetails.GoalAvailability(
                        g.getId(), g.getName(), g.getCurrentProgress()))
                .toList();

        return new InsufficientFreeBalanceDetails(freeBalance, shortfall, availableGoals);
    }

    public void requireSufficientFreeBalance(Account account, BigDecimal requiredAmount) {
        if (requiredAmount.compareTo(account.getCurrentBalance()) > 0) {
            BigDecimal shortfallAgainstBalance = requiredAmount.subtract(account.getCurrentBalance());
            throw ApiException.badRequest(
                    "This amount exceeds the account's balance by ₹" + formatAmount(shortfallAgainstBalance) +
                            " (balance: ₹" + formatAmount(account.getCurrentBalance()) + "). Reduce the amount or add " +
                            "funds to this account before retrying.");
        }

        BigDecimal freeBalance = getFreeBalance(account);
        if (requiredAmount.compareTo(freeBalance) <= 0) {
            return;
        }

        BigDecimal shortfallAgainstFree = requiredAmount.subtract(freeBalance);
        InsufficientFreeBalanceDetails details = buildInsufficientFreeBalanceDetails(account, requiredAmount);

        throw ApiException.badRequest(
                ApiErrorCodes.GOAL_ALLOCATION_SHORTFALL,
                "This amount exceeds the account's free balance by ₹" + formatAmount(shortfallAgainstFree) +
                        " (free balance: ₹" + formatAmount(freeBalance) + "). Add goalAllocations totalling at least ₹" +
                        formatAmount(shortfallAgainstFree) + " from the available goal(s) to cover the difference.",
                details);
    }

    /**
     * Net amount withdrawn from any goal (via the allocation ledger) in the given date range.
     * Every row today is a withdrawal — GoalAllocationDirection is a write-time-only parameter,
     * never persisted, and no INCREASE caller exists yet. If a goal-crediting feature ships,
     * this query and the entity will need a direction column and a DECREASE-only filter.
     */
    public BigDecimal sumWithdrawalsByDateRange(String userId, LocalDate from, LocalDate to) {
        BigDecimal result = allocationRepository.sumByUserIdAndDateRange(userId, from, to);
        return result != null ? result : BigDecimal.ZERO;
    }

    public BigDecimal sumWithdrawalsByCalendarMonth(String userId, int year, int month) {
        BigDecimal result = allocationRepository.sumByUserIdAndCalendarMonth(userId, year, month);
        return result != null ? result : BigDecimal.ZERO;
    }

    private String formatAmount(BigDecimal amount) {
        return amount.stripTrailingZeros().toPlainString();
    }

    private record BalanceCheckResult(boolean ok, boolean hardCeilingViolated, BigDecimal shortfall) {}

    private BalanceCheckResult evaluateResultingBalance(Account account, BigDecimal resultingBalance) {
        BigDecimal earmarked = activeGoalsOn(account).stream()
                .map(Goal::getCurrentProgress)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        if (resultingBalance.compareTo(earmarked) >= 0) {
            return new BalanceCheckResult(true, false, BigDecimal.ZERO);
        }
        if (resultingBalance.compareTo(BigDecimal.ZERO) < 0) {
            return new BalanceCheckResult(false, true, BigDecimal.ZERO);
        }
        return new BalanceCheckResult(false, false, earmarked.subtract(resultingBalance));
    }

    public boolean noLongerNeedsCoverage(Account account, BigDecimal resultingBalance) {
        return evaluateResultingBalance(account, resultingBalance).ok();
    }

    public boolean matchesShortfallExactly(Account account, BigDecimal resultingBalance, List<GoalAllocationItem> allocations) {
        BalanceCheckResult result = evaluateResultingBalance(account, resultingBalance);
        if (result.ok() || result.hardCeilingViolated()) return false;
        BigDecimal total = allocations.stream().map(GoalAllocationItem::amount).reduce(BigDecimal.ZERO, BigDecimal::add);
        return total.compareTo(result.shortfall()) == 0;
    }

    private InsufficientFreeBalanceDetails buildDetailsForResultingBalance(
            Account account, BigDecimal resultingBalance, BigDecimal shortfall) {
        List<InsufficientFreeBalanceDetails.GoalAvailability> availableGoals = activeGoalsOn(account).stream()
                .map(g -> new InsufficientFreeBalanceDetails.GoalAvailability(g.getId(), g.getName(), g.getCurrentProgress()))
                .toList();
        return new InsufficientFreeBalanceDetails(resultingBalance, shortfall, availableGoals);
    }

    /**
     * The one invariant guard, type-agnostic: given what an account's balance WOULD become after
     * some operation, enforce that it never drops below what's earmarked to active goals. Used by
     * any create/update path where the caller can supply a goalAllocations breakdown in the same
     * request to cover a shortfall.
     */
    public List<GoalAllocationItem> guardResultingBalance(
            Account account, BigDecimal resultingBalance, List<GoalAllocationItem> suppliedAllocations) {

        BalanceCheckResult result = evaluateResultingBalance(account, resultingBalance);
        if (result.ok()) return List.of();

        if (result.hardCeilingViolated()) {
            throw ApiException.badRequest(
                    "This would take the account balance below ₹0, by ₹" + formatAmount(resultingBalance.negate()) +
                            ". Reduce the amount, or add funds to this account, before retrying.");
        }

        if (suppliedAllocations == null || suppliedAllocations.isEmpty()) {
            throw ApiException.badRequest(
                    ApiErrorCodes.GOAL_ALLOCATION_SHORTFALL,
                    "This change leaves ₹" + formatAmount(result.shortfall()) + " earmarked across goals uncovered " +
                            "(resulting balance: ₹" + formatAmount(resultingBalance) + "). Add goalAllocations " +
                            "totalling at least ₹" + formatAmount(result.shortfall()) + " from the available goal(s) " +
                            "to cover the difference.",
                    buildDetailsForResultingBalance(account, resultingBalance, result.shortfall()));
        }

        BigDecimal suppliedTotal = suppliedAllocations.stream()
                .map(GoalAllocationItem::amount).reduce(BigDecimal.ZERO, BigDecimal::add);
        if (suppliedTotal.compareTo(result.shortfall()) != 0) {
            String verb = suppliedTotal.compareTo(result.shortfall()) < 0 ? "fall short of" : "exceed";
            throw ApiException.badRequest(
                    "Selected goal reductions (₹" + formatAmount(suppliedTotal) + ") " + verb +
                            " the shortfall (₹" + formatAmount(result.shortfall()) + "). They must add up to exactly this amount.");
        }

        return suppliedAllocations;
    }

    /**
     * Same invariant, for deletion: there's no request body to supply a fix in, so any shortfall
     * is a hard refusal with guidance, never an ask.
     */
    public void requireResultingBalanceSafeForDeletion(Account account, BigDecimal resultingBalance) {
        BalanceCheckResult result = evaluateResultingBalance(account, resultingBalance);
        if (result.ok()) return;

        if (result.hardCeilingViolated()) {
            throw ApiException.badRequest(
                    "Deleting this would take the account balance below ₹0, by ₹" +
                            formatAmount(resultingBalance.negate()) + ".");
        }

        throw ApiException.badRequest(
                ApiErrorCodes.GOAL_ALLOCATION_SHORTFALL,
                "Deleting this would leave ₹" + formatAmount(result.shortfall()) + " earmarked across goals uncovered " +
                        "(resulting balance: ₹" + formatAmount(resultingBalance) + "). Reduce or complete the affected " +
                        "goal(s) first, or edit this transaction's amount instead of deleting it, then retry.",
                buildDetailsForResultingBalance(account, resultingBalance, result.shortfall()));
    }
}
