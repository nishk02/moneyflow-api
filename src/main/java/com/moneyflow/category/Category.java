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
    @Id
    private String id;

    @Column(nullable = false, unique = true)
    private String name;

    private String icon;

    @Column(name = "is_system", nullable = false, columnDefinition = "INTEGER")
    private boolean system;

    @Column(name = "is_active", nullable = false, columnDefinition = "INTEGER")
    private boolean active;

    @Column(name = "display_order", nullable = false)
    private int displayOrder;
}
