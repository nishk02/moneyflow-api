package com.moneyflow.goal;

import com.moneyflow.account.Account;
import com.moneyflow.auth.User;
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
@Table(name = "goals")
@Getter
@Setter
@NoArgsConstructor
@EntityListeners(AuditingEntityListener.class)
public class Goal {
    @Id
    @UuidGenerator
    @Column (updatable = false, nullable = false)
    private String id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false, updatable = false)
    private User user;

    @Column(nullable = false)
    private String name;

    @Column(name = "target_amount", nullable = false, columnDefinition = "REAL")
    private BigDecimal targetAmount;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "account_id", nullable = false)
    private Account account;

    @Column(name = "start_date", nullable = false, columnDefinition = "TEXT")
    private LocalDate startDate;

    @Column(name = "end_date", nullable = false, columnDefinition = "TEXT")
    private LocalDate endDate;

    @Column(name = "current_progress", nullable = false, columnDefinition = "REAL")
    private BigDecimal currentProgress = BigDecimal.ZERO;

    @Column(name = "monthly_savings_required", columnDefinition = "REAL")
    private BigDecimal monthlySavingsRequired;

    @Column(name = "display_order", nullable = false)
    private int displayOrder = 0;

    @Column(nullable = false)
    private String status = "IN_PROGRESS";

    @Column(name = "is_active", nullable = false, columnDefinition = "INTEGER")
    private boolean active = true;

    @CreatedDate
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @LastModifiedDate
    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;
}