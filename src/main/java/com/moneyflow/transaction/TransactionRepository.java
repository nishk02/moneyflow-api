package com.moneyflow.transaction;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface TransactionRepository extends JpaRepository<Transaction, String> {
    List<Transaction> findByUserIdOrderByDateDescCreatedAtDesc(String userId);

    List<Transaction> findByUserIdAndCalendarYearAndCalendarMonthOrderByDateDescCreatedAtDesc(
            String userId, int calendarYear, int calendarMonth);

    List<Transaction> findByUserIdAndFinancialYearAndMonthOrderByDateDescCreatedAtDesc(
            String userId, String financialYear, int month);

    Optional<Transaction> findByIdAndUserId(String id, String userId);

    List<Transaction> findByUserIdAndAccountIdOrderByDateDescCreatedAtDesc(
            String userId, String accountId);

    @Query("SELECT COALESCE(SUM(t.amount), 0) FROM Transaction t " +
            "WHERE t.user.id = :userId " +
            "AND t.type IN :types " +
            "AND t.calendarYear = :year " +
            "AND t.calendarMonth = :month")
    java.math.BigDecimal sumByUserIdAndTypesAndMonth(
            @Param("userId") String userId,
            @Param("types") List<TransactionType> types,
            @Param("year") int year,
            @Param("month") int month);
}
