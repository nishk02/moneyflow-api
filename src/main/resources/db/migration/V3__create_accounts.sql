CREATE TABLE accounts
(
    id              TEXT      NOT NULL PRIMARY KEY,
    user_id         TEXT      NOT NULL REFERENCES users (id),
    name            TEXT      NOT NULL,
    type            TEXT      NOT NULL CHECK (type IN ('CASH', 'BANK', 'WALLET')),
    current_balance REAL      NOT NULL DEFAULT 0,
    currency        TEXT      NOT NULL DEFAULT 'INR',
    color_label     TEXT,
    display_order   INTEGER   NOT NULL DEFAULT 0,
    is_active       INTEGER   NOT NULL DEFAULT 1,
    created_at      TIMESTAMP NOT NULL,
    updated_at      TIMESTAMP NOT NULL,
    UNIQUE (user_id, name)
);

CREATE INDEX idx_accounts_user ON accounts (user_id);