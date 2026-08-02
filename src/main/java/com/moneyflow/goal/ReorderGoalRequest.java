package com.moneyflow.goal;

import java.util.List;

public record ReorderGoalRequest(
        List<ReorderItem> items
) {
    public record ReorderItem(String id, int displayOrder) {}
}
