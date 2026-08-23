package com.moneyflow.transaction;

import com.fasterxml.jackson.annotation.JsonInclude;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record PeriodTarget(Integer calendarYear, Integer calendarMonth, String financialYear, Integer financialMonth) {
    static PeriodTarget calendar(int year, int month) {
        return new PeriodTarget(year, month, null, null);
    }

    static PeriodTarget financial(String fy, int month) {
        return new PeriodTarget(null, null, fy, month);
    }
}
