package com.moneyflow.shared.util;

import com.moneyflow.transaction.FlowType;
import com.moneyflow.transaction.Transaction;
import com.moneyflow.transaction.TransactionSpecifications;
import org.springframework.data.jpa.domain.Specification;

import java.time.LocalDate;

public class FinancialYearUtil {
    public record FinancialMonth(String financialYear, int month) {}

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

    public static FinancialMonth previous(String financialYear, int month) {
        if (month > 1) {
            return new FinancialMonth(financialYear, month - 1);
        }
        return new FinancialMonth(shiftFinancialYear(financialYear, -1), 12);
    }

    public static FinancialMonth next(String financialYear, int month) {
        if (month < 12) {
            return new FinancialMonth(financialYear, month + 1);
        }
        return new FinancialMonth(shiftFinancialYear(financialYear, 1), 1);
    }

    private static String shiftFinancialYear(String financialYear, int delta) {
        int start = Integer.parseInt(financialYear.substring(2, 4));
        int end = Integer.parseInt(financialYear.substring(5, 7));
        return String.format("FY%02d-%02d",
                Math.floorMod(start + delta, 100),
                Math.floorMod(end + delta, 100));
    }
}
