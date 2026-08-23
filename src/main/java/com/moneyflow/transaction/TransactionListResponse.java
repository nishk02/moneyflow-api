package com.moneyflow.transaction;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.moneyflow.shared.dto.PageResponse;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record TransactionListResponse(
        PageResponse<TransactionResponse> transactions,
        PeriodTarget previousPeriod,
        PeriodTarget nextPeriod
) {
    public static TransactionListResponse of(PageResponse<TransactionResponse> transactions) {
        return new TransactionListResponse(transactions, null, null);
    }

    public static TransactionListResponse of(
            PageResponse<TransactionResponse> transactions,
            PeriodTarget previousPeriod,
            PeriodTarget nextPeriod) {
        return new TransactionListResponse(transactions, previousPeriod, nextPeriod);
    }
}