package com.moneyflow.transaction;

import java.util.List;
import java.util.Map;

public record AvailablePeriodsResponse(
        List<Integer> years,
        Map<Integer, List<Integer>> monthsByYear
) {
}
