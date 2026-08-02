package com.moneyflow.account;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface AccountRepository extends JpaRepository<Account, String> {
    List<Account> findByUserIdAndActiveTrue(String userId);

    Optional<Account> findByIdAndUserId(String id, String userId);

    boolean existsByUserIdAndNameIgnoreCase(String userId, String name);

    List<Account> findByUserIdAndTypeAndActiveTrue(String userId, String type);
}