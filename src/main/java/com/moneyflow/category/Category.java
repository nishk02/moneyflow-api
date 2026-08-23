package com.moneyflow.category;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "categories")
@Getter
@NoArgsConstructor
public class Category {
    /** System-reserved category for BR-01 opening-balance SETTLEMENT transactions.
     *  Never user-selectable — see TransactionService's internal-category guard. */
    public static final String OPENING_BALANCE_CATEGORY_ID = "cat-35";

    @Id
    private String id;

    @Column(nullable = false, unique = true)
    private String name;

    private String icon;

    @Column(name = "is_system", nullable = false, columnDefinition = "INTEGER")
    private boolean system;

    @Column(name = "is_active", nullable = false, columnDefinition = "INTEGER")
    private boolean active;

    @Column(name = "is_internal", nullable = false, columnDefinition = "INTEGER")
    private boolean internal;

    @Column(name = "display_order", nullable = false)
    private int displayOrder;
}
