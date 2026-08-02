CREATE TABLE goals
(
    id                          TEXT        NOT NULL PRIMARY KEY,
    user_id                     TEXT        NOT NULL REFERENCES users (id),
    name                        TEXT        NOT NULL,
    target_amount               REAL        NOT NULL CHECK (target_amount > 0),
    account_id                  TEXT        NOT NULL REFERENCES accounts (id),
    start_date                  TEXT        NOT NULL,
    end_date                    TEXT        NOT NULL,
    current_progress            REAL        NOT NULL DEFAULT 0,
    monthly_savings_required    REAL,
    display_order               INTEGER     NOT NULL DEFAULT 0,
    status                      TEXT        NOT NULL DEFAULT 'IN_PROGRESS' CHECK (status IN (
                                    'IN_PROGRESS', 'UPCOMING', 'COMPLETED', 'PAUSED'
                                )),
    is_active                   INTEGER     NOT NULL DEFAULT 1,
    created_at                  TIMESTAMP   NOT NULL,
    updated_at                  TIMESTAMP   NOT NULL,
    UNIQUE (user_id, name)
);

CREATE INDEX idx_goals_user_status ON goals (user_id, status) WHERE is_active = 1;
