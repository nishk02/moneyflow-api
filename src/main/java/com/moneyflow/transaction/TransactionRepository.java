package com.moneyflow.transaction;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

@Repository
public interface TransactionRepository extends JpaRepository<Transaction, String> {
    Page<Transaction> findByUserIdOrderByDateDescCreatedAtDesc(String userId, Pageable pageable);

    Page<Transaction> findByUserIdAndCalendarYearAndCalendarMonthOrderByDateDescCreatedAtDesc(
            String userId, int calendarYear, int calendarMonth, Pageable pageable);

    Page<Transaction> findByUserIdAndFinancialYearAndMonthOrderByDateDescCreatedAtDesc(
            String userId, String financialYear, int month, Pageable pageable);

    Optional<Transaction> findByIdAndUserId(String id, String userId);

    List<Transaction> findByUserIdAndAccountIdOrderByDateDescCreatedAtDesc(
            String userId, String accountId);

    List<Transaction> findTop5ByUserIdOrderByDateDescCreatedAtDesc(String userId);

    boolean existsByUserIdAndCalendarYearAndCalendarMonth(
            String userId, int calendarYear, int calendarMonth);

    boolean existsByUserIdAndFinancialYearAndMonth(
            String userId, String financialYear, int month);

    @Query("SELECT COALESCE(SUM(t.amount), 0) FROM Transaction t " +
            "WHERE t.user.id = :userId " +
            "AND t.type IN :types " +
            "AND t.calendarYear = :year " +
            "AND t.calendarMonth = :month")
    BigDecimal sumByUserIdAndTypesAndMonth(
            @Param("userId") String userId,
            @Param("types") List<TransactionType> types,
            @Param("year") int year,
            @Param("month") int month);

    @Query("SELECT COALESCE(SUM(t.amount), 0) FROM Transaction t " +
            "WHERE t.user.id = :userId " +
            "AND t.type IN :types " +
            "AND t.date >= :from " +
            "AND t.date <= :to")
    BigDecimal sumByUserIdAndTypesAndDateRange(
            @Param("userId") String userId,
            @Param("types") List<TransactionType> types,
            @Param("from") LocalDate from,
            @Param("to") LocalDate to);

    @Query("SELECT COALESCE(SUM(t.amount), 0) FROM Transaction t " +
            "WHERE t.user.id = :userId " +
            "AND t.type = 'TRANSFER' " +
            "AND t.toGoalId IS NOT NULL " +
            "AND t.date >= :from " +
            "AND t.date <= :to")
    BigDecimal sumTransferToGoalByDateRange(
            @Param("userId") String userId,
            @Param("from") LocalDate from,
            @Param("to") LocalDate to);
}
