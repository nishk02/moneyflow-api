CREATE TABLE transactions
(
    id                  TEXT        NOT NULL PRIMARY KEY,
    user_id             TEXT        NOT NULL REFERENCES users (id),
    date                TEXT        NOT NULL,
    type                TEXT        NOT NULL CHECK (type IN (
                            'FIXED_EXPENSE', 'VARIABLE_EXPENSE', 'INCOME',
                            'LENDING', 'BORROWING', 'REPAYMENT',
                            'SETTLEMENT', 'TRANSFER'
                        )),
    category_id         TEXT        NOT NULL REFERENCES categories (id),
    account_id          TEXT        NOT NULL REFERENCES accounts (id),
    to_account_id       TEXT        REFERENCES accounts (id),
    to_goal_id          TEXT,
    amount              REAL        NOT NULL,
    notes               TEXT,
    financial_year      TEXT        NOT NULL,
    month               INTEGER     NOT NULL,
    calendar_month      INTEGER     NOT NULL,
    calendar_year       INTEGER     NOT NULL,
    is_planned          INTEGER     NOT NULL DEFAULT 0,
    planned_amount_id   TEXT,
    created_at          TIMESTAMP   NOT NULL,
    updated_at          TIMESTAMP   NOT NULL,
    CHECK (
        type != 'TRANSFER' OR (
            (to_account_id IS NOT NULL AND to_goal_id IS NULL) OR
            (to_account_id IS NULL AND to_goal_id IS NOT NULL)
        )
    )
);

CREATE INDEX idx_transactions_user_date ON transactions (user_id, date DESC);
CREATE INDEX idx_transactions_user_fy_month ON transactions (user_id, financial_year, month);
CREATE INDEX idx_transactions_account ON transactions (account_id);