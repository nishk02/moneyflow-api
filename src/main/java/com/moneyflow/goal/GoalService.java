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
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class GoalService {
    private final GoalRepository goalRepository;
    private final AccountRepository accountRepository;
    private final UserRepository userRepository;
    private static final Set<String> VALID_GOAL_STATUSES = Set.of("IN_PROGRESS", "COMPLETED");

    @Transactional(readOnly = true)
    public List<GoalResponse> getGoals(String userId) {
        return goalRepository
                .findByUserIdAndActiveTrueOrderByDisplayOrderAsc(userId)
                .stream().map(GoalResponse::from).toList();
    }

    @Transactional(readOnly = true)
    public List<GoalResponse> getGoalsByStatus(String userId, String status) {
        String normalizedStatus = status.toUpperCase();

        if (!VALID_GOAL_STATUSES.contains(normalizedStatus)) {
            throw ApiException.badRequest("status must be one of: IN_PROGRESS, COMPLETED");
        }

        List<Goal> goals = goalRepository.findByUserIdAndStatusAndActiveTrue(userId, normalizedStatus);

        // Ongoing (started) goals before upcoming (not yet started) ones; displayOrder breaks ties within each group.
        Comparator<Goal> order = normalizedStatus.equals("IN_PROGRESS")
                ? Comparator.comparing(GoalService::isUpcoming).thenComparing(Goal::getDisplayOrder)
                : Comparator.comparing(Goal::getDisplayOrder);

        return goals.stream().sorted(order).map(GoalResponse::from).toList();
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

        if (request.startDate() != null && request.startDate().isBefore(LocalDate.now())) {
            throw ApiException.badRequest("Goal start date cannot be in the past.");
        }

        if (request.endDate() != null && request.endDate().isBefore(LocalDate.now())) {
            throw ApiException.badRequest("Goal end date cannot be in the past.");
        }

        if (request.startDate() != null && request.endDate() != null) {
            long monthsBetween = monthsBetweenIgnoringDay(request.startDate(), request.endDate());
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

        goal.setMonthlySavingsRequired(calculatePlannedMonthlySavings(goal));

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

        // Account can only change while currentProgress is still 0 - otherwise the goal would
        // claim money the new account never actually funded (same shape as Bug #1).
        if (request.accountId() != null && !request.accountId().equals(goal.getAccount().getId())) {
            BigDecimal progress = goal.getCurrentProgress() != null ? goal.getCurrentProgress() : BigDecimal.ZERO;
            if (progress.compareTo(BigDecimal.ZERO) != 0) {
                throw ApiException.badRequest(
                        "Cannot move '" + goal.getName() + "' to a different account while it has ₹" + progress +
                                " in progress. Bring its progress to ₹0 first (e.g. withdraw it out)," +
                                " or create a new goal instead.");
            }
            if (!account.isActive()) {
                throw ApiException.badRequest("Destination account is inactive.");
            }
        }

        // startDate is immutable (it anchors the frozen "planned" pace). endDate can move
        // either way, as long as it clears the 1-month gap and isn't in the past.
        if (request.endDate() != null) {
            if (request.endDate().isBefore(LocalDate.now())) {
                throw ApiException.badRequest("Goal end date cannot be in the past.");
            }

            long monthsBetween = monthsBetweenIgnoringDay(goal.getStartDate(), request.endDate());
            if (monthsBetween < 1) {
                throw ApiException.badRequest("The end date must be at least 1 month after the start date.");
            }
        }

        if (request.name() != null) {
            goal.setName(request.name());
        }

        // Check if values affecting the planned-pace calculation are changing
        boolean budgetOrTimelineChanged = request.targetAmount() != null || request.endDate() != null;

        if (request.targetAmount() != null) {
            goal.setTargetAmount(request.targetAmount());
        }

        if (request.accountId() != null) {
            goal.setAccount(account);
        }

        if (request.endDate() != null) {
            goal.setEndDate(request.endDate());
        }

        if (budgetOrTimelineChanged) {
            BigDecimal updatedPlannedSavings = calculatePlannedMonthlySavings(goal);
            goal.setMonthlySavingsRequired(updatedPlannedSavings);
        }

        Goal savedGoal = goalRepository.save(goal);

        return GoalResponse.from(savedGoal);
    }

    /**
     * Only allowed once currentProgress has reached targetAmount - confirms the goal was
     * reached, not a way to close it out early.
     */
    @Transactional
    public GoalResponse markGoalComplete(String userId, String goalId) {
        Goal goal = goalRepository.findByIdAndUserId(goalId, userId)
                .orElseThrow(() -> ApiException.notFound("Goal not found"));

        if ("COMPLETED".equalsIgnoreCase(goal.getStatus())) {
            throw ApiException.badRequest("This goal is already marked complete.");
        }

        BigDecimal progress = goal.getCurrentProgress() != null ? goal.getCurrentProgress() : BigDecimal.ZERO;
        if (progress.compareTo(goal.getTargetAmount()) < 0) {
            BigDecimal remaining = goal.getTargetAmount().subtract(progress);
            throw ApiException.badRequest(
                    "'" + goal.getName() + "' hasn't reached its target yet — ₹" + remaining +
                            " still remaining. Keep saving, or lower the target if you want to close it out now.");
        }

        goal.setStatus("COMPLETED");
        Goal savedGoal = goalRepository.save(goal);
        return GoalResponse.from(savedGoal);
    }

    /** Undoes markGoalComplete, e.g. if completed by mistake. */
    @Transactional
    public GoalResponse reopenGoal(String userId, String goalId) {
        Goal goal = goalRepository.findByIdAndUserId(goalId, userId)
                .orElseThrow(() -> ApiException.notFound("Goal not found"));

        if (!"COMPLETED".equalsIgnoreCase(goal.getStatus())) {
            throw ApiException.badRequest("Only a completed goal can be reopened.");
        }

        goal.setStatus("IN_PROGRESS");
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

    /** Static plan: targetAmount / total months, ignores progress. */
    private BigDecimal calculatePlannedMonthlySavings(Goal goal) {
        if (goal.getTargetAmount() == null || goal.getEndDate() == null || goal.getStartDate() == null) {
            return BigDecimal.ZERO;
        }

        if (goal.getTargetAmount().compareTo(BigDecimal.ZERO) <= 0) {
            return BigDecimal.ZERO;
        }

        long totalMonths = monthsBetweenIgnoringDay(goal.getStartDate(), goal.getEndDate());

        if (totalMonths <= 0) {
            return goal.getTargetAmount();
        }

        return goal.getTargetAmount().divide(BigDecimal.valueOf(totalMonths), 1, RoundingMode.HALF_UP);
    }

    /** Months between two dates, ignoring the day-of-month component. */
    private static long monthsBetweenIgnoringDay(LocalDate start, LocalDate end) {
        return ChronoUnit.MONTHS.between(start.withDayOfMonth(1), end.withDayOfMonth(1));
    }

    private static boolean isUpcoming(Goal goal) {
        return goal.getStartDate() != null && goal.getStartDate().isAfter(LocalDate.now());
    }
}
