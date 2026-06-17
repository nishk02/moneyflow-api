package com.moneyflow.category;

public record CategoryResponse(
        String id,
        String name,
        String icon,
        boolean isSystem,
        boolean isActive,
        int displayOrder
) {
    public static CategoryResponse from(Category category) {
        return new CategoryResponse(
                category.getId(),
                category.getName(),
                category.getIcon(),
                category.isSystem(),
                category.isActive(),
                category.getDisplayOrder()
        );
    }
}
