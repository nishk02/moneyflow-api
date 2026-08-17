package com.moneyflow.transaction;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.moneyflow.shared.dto.PageResponse;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record TransactionListResponse(
        PageResponse<TransactionResponse> transactions,
        Boolean hasPreviousMonthData,
        Boolean hasNextMonthData
) {
    public static TransactionListResponse of(PageResponse<TransactionResponse> transactions) {
        return new TransactionListResponse(transactions, null, null);
    }

    public static TransactionListResponse of(
            PageResponse<TransactionResponse> transactions,
            boolean hasPreviousMonthData,
            boolean hasNextMonthData) {
        return new TransactionListResponse(transactions, hasPreviousMonthData, hasNextMonthData);
    }
}