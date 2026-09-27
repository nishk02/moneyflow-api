package com.moneyflow.shared.exception;

/**
 * Machine-readable codes for the few errors the frontend needs to branch on,
 * not every validation error. Everything else keeps ApiException's default
 * "BAD_REQUEST" code and is just displayed as-is.
 */
public class ApiErrorCodes {
    private ApiErrorCodes() {
        // Constant holder - not instantiable
    }

    /**
     * The amount can't be covered without choosing which goal(s) to draw
     * from or reduce. Paired with an InsufficientFreeBalanceDetails payload
     * (freeBalance, shortfall, availableGoals) so the client can render a
     * goal-picker from the error. Same situation across three flows:
     * create (BR-19), edit-reconciliation (BR-19), balance correction (BR-20).
     */
    public static final String GOAL_ALLOCATION_SHORTFALL = "GOAL_ALLOCATION_SHORTFALL";
}