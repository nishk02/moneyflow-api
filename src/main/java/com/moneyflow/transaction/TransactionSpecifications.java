package com.moneyflow.transaction;

import org.springframework.data.jpa.domain.Specification;

public class TransactionSpecifications {
    private TransactionSpecifications() {
    }

    public static Specification<Transaction> belongsToUser(String userId) {
        return (root, query, criteriaBuilder) -> criteriaBuilder.equal(root.get("user").get("id"), userId);
    }

    public static Specification<Transaction> inCalendarMonth(int year, int month) {
        return (root, query, criteriaBuilder) -> criteriaBuilder.and(
                criteriaBuilder.equal(root.get("calendarYear"), year),
                criteriaBuilder.equal(root.get("calendarMonth"), month));
    }

    public static Specification<Transaction> inFinancialMonth(String financialYear, int month) {
        return (root, query, criteriaBuilder) -> criteriaBuilder.and(
                criteriaBuilder.equal(root.get("financialYear"), financialYear),
                criteriaBuilder.equal(root.get("month"), month));
    }

    public static Specification<Transaction> hasFlowType(FlowType flowType) {
        return (root, query, criteriaBuilder) -> root.get("type").in(flowType.getTypes());
    }
}
