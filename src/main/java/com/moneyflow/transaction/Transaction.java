package com.moneyflow.transaction;

import com.moneyflow.account.Account;
import com.moneyflow.auth.User;
import com.moneyflow.category.Category;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.UuidGenerator;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

@Entity
@Table(name = "transactions")
@Getter
@Setter
@NoArgsConstructor
@EntityListeners(AuditingEntityListener.class)
public class Transaction {
    @Id
    @UuidGenerator
    @Column (updatable = false, nullable = false)
    private String id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false, updatable = false)
    private User user;

    @Column(nullable = false, columnDefinition = "TEXT")
    private LocalDate date;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private TransactionType type;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "category_id", nullable = false)
    private Category category;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "account_id", nullable = false)
    private Account account;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "to_account_id")
    private Account toAccount;

    @Column(name = "to_goal_id")
    private String toGoalId;

    @Column(name = "amount", nullable = false, columnDefinition = "REAL")
    private BigDecimal amount;

    @Column(name = "notes")
    private String notes;

    @Column(name = "financial_year", nullable = false)
    private String financialYear;

    @Column(name = "month", nullable = false)
    private int month;

    @Column(name = "calendar_month", nullable = false)
    private int calendarMonth;

    @Column(name = "calendar_year", nullable = false)
    private int calendarYear;

    @Column(name = "is_planned", nullable = false, columnDefinition = "INTEGER")
    private boolean planned;

    @Column(name = "planned_amount_id")
    private String plannedAmountId;

    @CreatedDate
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @LastModifiedDate
    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;
}
