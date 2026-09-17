package com.moneyflow.transaction;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface TransactionGoalAllocationRepository extends JpaRepository<TransactionGoalAllocation, String> {
    List<TransactionGoalAllocation> findByTransactionId(String transactionId);
    List<TransactionGoalAllocation> findByTransactionIdIn(List<String> transactionIds);
}