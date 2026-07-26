package com.moneyflow.shared.util;

import com.moneyflow.transaction.Transaction;

import java.time.LocalDate;

public class FinancialYearUtil {
    private FinancialYearUtil() {
        // Utility class - not instantiable
    }

    public static void applyDerivedDateFields(
            Transaction transaction, LocalDate date) {
        int calendarMonth = date.getMonthValue();
        int calendarYear = date.getYear();

        transaction.setCalendarMonth(calendarMonth);
        transaction.setCalendarYear(calendarYear);

        if (calendarMonth >= 4) {
            int fyStart = calendarYear % 100;
            int fyEnd = (calendarYear + 1) % 100;
            transaction.setFinancialYear(String.format("FY%02d-%02d", fyStart, fyEnd));
            transaction.setMonth(calendarMonth - 3);
        } else {
            int fyStart = (calendarYear - 1) % 100;
            int fyEnd = calendarYear % 100;
            transaction.setFinancialYear(String.format("FY%02d-%02d", fyStart, fyEnd));
            transaction.setMonth(calendarMonth + 9);
        }
    }
}
