package com.moneyflow.transaction;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

@Repository
public interface TransactionGoalAllocationRepository extends JpaRepository<TransactionGoalAllocation, String> {
    List<TransactionGoalAllocation> findByTransactionId(String transactionId);
    List<TransactionGoalAllocation> findByTransactionIdIn(List<String> transactionIds);

    @Query("SELECT COALESCE(SUM(a.amount), 0) FROM TransactionGoalAllocation a " +
            "WHERE a.transaction.user.id = :userId " +
            "AND a.transaction.date >= :from " +
            "AND a.transaction.date <= :to")
    BigDecimal sumByUserIdAndDateRange(
            @Param("userId") String userId,
            @Param("from") LocalDate from,
            @Param("to") LocalDate to);

    @Query("SELECT COALESCE(SUM(a.amount), 0) FROM TransactionGoalAllocation a " +
            "WHERE a.transaction.user.id = :userId " +
            "AND a.transaction.calendarYear = :year " +
            "AND a.transaction.calendarMonth = :month")
    BigDecimal sumByUserIdAndCalendarMonth(
            @Param("userId") String userId,
            @Param("year") int year,
            @Param("month") int month);
}