package com.moneyflow.goal;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface GoalRepository extends JpaRepository<Goal, String> {
    List<Goal> findByUserIdAndActiveTrueOrderByDisplayOrderAsc(String userId);

    Optional<Goal> findByIdAndUserId(String id, String userId);

    boolean existsByUserIdAndNameIgnoreCase(String userId, String name);

    List<Goal> findByUserIdAndStatusAndActiveTrue(String userId, String status);

    boolean existsByAccountIdAndActiveTrueAndStatusNot(String accountId, String status);
}
