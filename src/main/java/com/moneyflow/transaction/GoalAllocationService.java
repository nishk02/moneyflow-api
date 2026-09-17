package com.moneyflow.transaction;

import com.moneyflow.account.Account;
import com.moneyflow.goal.Goal;
import com.moneyflow.goal.GoalRepository;
import com.moneyflow.shared.exception.ApiException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
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
                        "Cannot allocate ₹" + item.amount() + " from goal '" + goal.getName() +
                                "' — only ₹" + goal.getCurrentProgress() + " is currently earmarked there.");
            }
            if (direction == GoalAllocationDirection.INCREASE
                    && goal.getCurrentProgress().add(item.amount()).compareTo(goal.getTargetAmount()) > 0) {
                BigDecimal headroom = goal.getTargetAmount().subtract(goal.getCurrentProgress());
                throw ApiException.badRequest(
                        "Cannot allocate ₹" + item.amount() + " to goal '" + goal.getName() +
                                "' — only ₹" + headroom + " is left to reach its target.");
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

    public void requireSufficientFreeBalance(Account account, BigDecimal requiredAmount) {
        BigDecimal freeBalance = getFreeBalance(account);
        if (requiredAmount.compareTo(freeBalance) > 0) {
            throw ApiException.badRequest(
                    "This amount exceeds the account's free balance. Choose which goal(s) to draw the rest from.",
                    buildInsufficientFreeBalanceDetails(account, requiredAmount));
        }
    }
}
