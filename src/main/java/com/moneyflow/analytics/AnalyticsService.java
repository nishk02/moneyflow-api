package com.moneyflow.analytics;

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

    @Transactional(readOnly = true)
    public AnalyticsResponse getCashflowSummary(
            String userId, String mode, LocalDate anchor, LocalDate from, LocalDate to) {
        LocalDate[] range = resolveDateRange(mode, anchor, from, to);
        LocalDate resolvedFrom = range[0];
        LocalDate resolvedTo = range[1];

        BigDecimal income = sumByTypes(userId, resolvedFrom, resolvedTo, List.of(TransactionType.INCOME));

        BigDecimal expense = sumByTypes(userId, resolvedFrom, resolvedTo,
                List.of(TransactionType.FIXED_EXPENSE, TransactionType.VARIABLE_EXPENSE));

        BigDecimal savings = sumTransferToGoal(userId, resolvedFrom, resolvedTo);

        BigDecimal savingsRate = computeRate(savings, income);
        BigDecimal debtRatio = computeDebtRatio(userId, resolvedFrom, resolvedTo, income);

        return new AnalyticsResponse(
                new AnalyticsResponse.Period(resolvedFrom, resolvedTo, mode),
                income,
                expense,
                savings,
                savingsRate,
                debtRatio);
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
        BigDecimal result = transactionRepository.sumTransferToGoalByDateRange(userId, from, to);
        return result != null ? result : BigDecimal.ZERO;
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
