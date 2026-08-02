package com.moneyflow.goal;

import com.moneyflow.account.Account;
import com.moneyflow.account.AccountRepository;
import com.moneyflow.auth.User;
import com.moneyflow.auth.UserRepository;
import com.moneyflow.shared.exception.ApiException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class GoalService {
    private final GoalRepository goalRepository;
    private final AccountRepository accountRepository;
    private final UserRepository userRepository;

    @Transactional(readOnly = true)
    public List<GoalResponse> getGoals(String userId) {
        return goalRepository
                .findByUserIdAndActiveTrueOrderByDisplayOrderAsc(userId)
                .stream().map(GoalResponse::from).toList();
    }

    @Transactional(readOnly = true)
    public List<GoalResponse> getGoalsByStatus(String userId, String status) {
        return goalRepository.findByUserIdAndStatusAndActiveTrue(userId, status)
                .stream().map(GoalResponse::from).toList();
    }

    @Transactional(readOnly = true)
    public GoalResponse getGoal(String userId, String goalId) {
        return goalRepository
                .findByIdAndUserId(goalId, userId)
                .map(GoalResponse::from)
                .orElseThrow(() -> ApiException.notFound("Goal not found"));
    }

    @Transactional
    public GoalResponse createGoal(String userId, CreateGoalRequest request) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> ApiException.notFound("User not found"));

        Account account = accountRepository.findByIdAndUserId(request.accountId(), userId)
                .orElseThrow(() -> ApiException.notFound("Account not found"));

        if (goalRepository.existsByUserIdAndNameIgnoreCase(userId, request.name())) {
            throw ApiException.conflict("A goal named '" + request.name() + "' already exists");
        }

        if (request.targetAmount() != null && request.targetAmount().compareTo(BigDecimal.ZERO) <= 0) {
            throw ApiException.badRequest("Target amount must be greater than zero.");
        }

        if (request.startDate() != null && request.endDate() != null) {
            long monthsBetween = ChronoUnit.MONTHS.between(request.startDate(), request.endDate());
            if (monthsBetween < 1) {
                throw ApiException.badRequest("The end date must be at least 1 month after the start date.");
            }
        }

        Goal goal = new Goal();

        goal.setUser(user);
        goal.setName(request.name());
        goal.setTargetAmount(request.targetAmount());
        goal.setAccount(account);
        goal.setStartDate(request.startDate());
        goal.setEndDate(request.endDate());
        goal.setCurrentProgress(BigDecimal.ZERO);

        goal.setMonthlySavingsRequired(calculateMonthlySavingsRequired(goal));

        Goal savedGoal = goalRepository.save(goal);
        return GoalResponse.from(savedGoal);
    }

    @Transactional
    public GoalResponse updateGoal(String userId, String goalId, UpdateGoalRequest request) {
        Goal goal = goalRepository.findByIdAndUserId(goalId, userId)
                .orElseThrow(() -> ApiException.notFound("Goal not found"));

        final Account account = (request.accountId() != null)
                ? accountRepository.findByIdAndUserId(request.accountId(), userId)
                .orElseThrow(() -> ApiException.notFound("Account not found"))
                : null;

        if ("COMPLETED".equalsIgnoreCase(goal.getStatus())) {
            throw ApiException.badRequest("Completed goals cannot be modified.");
        }

        if (request.name() != null
                && !request.name().equalsIgnoreCase(goal.getName())
                && goalRepository.existsByUserIdAndNameIgnoreCase(userId, request.name())
        ) {
            throw ApiException.conflict("A goal named '" + request.name() + "' already exists");
        }

        if (request.targetAmount() != null) {
            if (request.targetAmount().compareTo(BigDecimal.ZERO) <= 0) {
                throw ApiException.badRequest("Target amount must be greater than zero.");
            }

            BigDecimal progress = goal.getCurrentProgress() != null ? goal.getCurrentProgress() : BigDecimal.ZERO;
            if (request.targetAmount().compareTo(progress) < 0) {
                throw ApiException.badRequest("Target amount cannot be less than your current progress of " + progress + ".");
            }
        }

        if (request.startDate() != null || request.endDate() != null) {
            LocalDate finalStartDate = request.startDate() != null ? request.startDate() : goal.getStartDate();
            LocalDate finalEndDate = request.endDate() != null ? request.endDate() : goal.getEndDate();

            if (finalStartDate != null && finalEndDate != null) {
                long monthsBetween = ChronoUnit.MONTHS.between(finalStartDate, finalEndDate);

                if (monthsBetween < 1) {
                    throw ApiException.badRequest("The end date must be at least 1 month after the start date.");
                }
            }
        }

        if (request.name() != null) {
            goal.setName(request.name());
        }

        // Check if values affecting savings calculation are changing
        boolean budgetOrTimelineChanged = request.targetAmount() != null || request.endDate() != null;

        if (request.targetAmount() != null) {
            goal.setTargetAmount(request.targetAmount());
        }

        if (request.accountId() != null) {
            goal.setAccount(account);
        }

        if (request.startDate() != null) {
            goal.setStartDate(request.startDate());
        }

        if (request.endDate() != null) {
            goal.setEndDate(request.endDate());
        }

        if (budgetOrTimelineChanged) {
            BigDecimal updatedSavings = calculateMonthlySavingsRequired(goal);
            goal.setMonthlySavingsRequired(updatedSavings);
        }

        Goal savedGoal = goalRepository.save(goal);

        return GoalResponse.from(savedGoal);
    }

    @Transactional
    public void deleteGoal(String userId, String goalId) {
        Goal goal = goalRepository.findByIdAndUserId(goalId, userId)
                .orElseThrow(() -> ApiException.notFound("Goal not found"));

        goal.setActive(false);
        goalRepository.save(goal);
    }

    @Transactional
    public void reorderGoals(String userId, ReorderGoalRequest request) {
        if (request == null || request.items() == null || request.items().isEmpty()) {
            return;
        }

        List<Goal> userGoals = goalRepository.findByUserIdAndActiveTrueOrderByDisplayOrderAsc(userId);

        Map<String, Goal> goalMap = userGoals.stream().collect(Collectors.toMap(Goal::getId, goal -> goal));

        for (ReorderGoalRequest.ReorderItem item : request.items()) {
            Goal goal = goalMap.get(item.id());

            if (goal != null) {
                goal.setDisplayOrder(item.displayOrder());
            }
        }

        goalRepository.saveAll(userGoals);
    }

    private BigDecimal calculateMonthlySavingsRequired(Goal goal) {
        if (goal.getTargetAmount() == null || goal.getEndDate() == null) {
            return BigDecimal.ZERO;
        }

        BigDecimal currentProgress = goal.getCurrentProgress() != null ? goal.getCurrentProgress() : BigDecimal.ZERO;
        BigDecimal remainingAmount = goal.getTargetAmount().subtract(currentProgress);

        if (remainingAmount.compareTo(BigDecimal.ZERO) <= 0) {
            return BigDecimal.ZERO;
        }

        LocalDate baselineDate = goal.getStartDate() != null ? goal.getStartDate() : LocalDate.now();
        long monthsRemaining = ChronoUnit.MONTHS.between(baselineDate, goal.getEndDate());

        if (monthsRemaining <= 0) {
            return remainingAmount;
        }

        return remainingAmount.divide(BigDecimal.valueOf(monthsRemaining), 1, RoundingMode.HALF_UP);
    }
}
