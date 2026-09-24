package com.moneyflow.analytics;

import com.moneyflow.shared.exception.ApiException;
import com.moneyflow.transaction.GoalAllocationService;
import com.moneyflow.transaction.TransactionRepository;
import com.moneyflow.transaction.TransactionType;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.temporal.TemporalAdjusters;
import java.util.List;

@Service
@RequiredArgsConstructor
public class AnalyticsService {
    private final TransactionRepository transactionRepository;
    private final GoalAllocationService goalAllocationService;

    @Transactional(readOnly = true)
    public AnalyticsResponse getCashflowSummary(String userId, LocalDate from, LocalDate to) {
        validateRange(from, to);

        BigDecimal income = sumByTypes(userId, from, to, List.of(TransactionType.INCOME));

        BigDecimal expense = sumByTypes(userId, from, to,
                List.of(TransactionType.FIXED_EXPENSE, TransactionType.VARIABLE_EXPENSE));

        BigDecimal savings = sumTransferToGoal(userId, from, to);

        BigDecimal savingsRate = computeRate(savings, income);
        BigDecimal debtRatio = computeDebtRatio(userId, from, to, income);

        return new AnalyticsResponse(
                new AnalyticsResponse.Period(from, to),
                income,
                expense,
                savings,
                savingsRate,
                debtRatio);
    }

    private void validateRange(LocalDate from, LocalDate to) {
        if (from == null || to == null) {
            throw ApiException.badRequest("Both 'from' and 'to' are required.");
        }
        if (from.isAfter(to)) {
            throw ApiException.badRequest("'from' cannot be after 'to'.");
        }
    }

    private LocalDate[] resolveDateRange(String mode, LocalDate anchor, LocalDate from, LocalDate to) {
        // CUSTOM mode - explicit range, use directly
        if ("CUSTOM".equalsIgnoreCase(mode) && from != null && to != null) {
            return new LocalDate[]{from, to};
        }

        // Use today as anchor if none provided
        LocalDate pivotDate = anchor != null ? anchor : LocalDate.now();

        // WEEKLY mode - Monday to Sunday of the week containing pivotDate
        if ("WEEKLY".equalsIgnoreCase(mode)) {
            LocalDate monday = pivotDate.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
            LocalDate sunday = monday.plusDays(6);
            return new LocalDate[]{monday, sunday};
        }

        // MONTHLY mode (default) - 1st to last day of month containing pivotDate
        LocalDate firstDay = pivotDate.with(TemporalAdjusters.firstDayOfMonth());
        LocalDate lastDay = pivotDate.with(TemporalAdjusters.lastDayOfMonth());
        return new LocalDate[]{firstDay, lastDay};
    }

    private BigDecimal sumByTypes(String userId, LocalDate from, LocalDate to, List<TransactionType> types) {
        BigDecimal result = transactionRepository.sumByUserIdAndTypesAndDateRange(userId, types, from, to);

        return result != null ? result : BigDecimal.ZERO;
    }

    private BigDecimal sumTransferToGoal(String userId, LocalDate from, LocalDate to) {
        BigDecimal grossDeposits = transactionRepository.sumTransferToGoalByDateRange(userId, from, to);
        if (grossDeposits == null) {
            grossDeposits = BigDecimal.ZERO;
        }

        BigDecimal withdrawals = goalAllocationService.sumWithdrawalsByDateRange(userId, from, to);

        return grossDeposits.subtract(withdrawals);
    }

    private BigDecimal computeRate(BigDecimal part, BigDecimal total) {
        if (total == null || total.compareTo(BigDecimal.ZERO) == 0) {
            return null;
        }
        return part.multiply(new BigDecimal("100")).divide(total, 1, RoundingMode.HALF_UP);
    }

    private BigDecimal computeDebtRatio(String userId, LocalDate from, LocalDate to, BigDecimal income) {
        if (income == null || income.compareTo(BigDecimal.ZERO) == 0) {
            return null;
        }
        BigDecimal repayments = sumByTypes(userId, from, to, List.of(TransactionType.REPAYMENT));
        return computeRate(repayments, income);
    }
}
