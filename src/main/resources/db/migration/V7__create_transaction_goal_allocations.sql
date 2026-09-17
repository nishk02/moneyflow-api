CREATE TABLE transaction_goal_allocations
(
    id              TEXT        NOT NULL PRIMARY KEY,
    transaction_id  TEXT        NOT NULL REFERENCES transactions (id) ON DELETE CASCADE,
    goal_id         TEXT        NOT NULL REFERENCES goals (id),
    amount          REAL        NOT NULL CHECK (amount > 0),
    created_at      TIMESTAMP   NOT NULL,
    UNIQUE (transaction_id, goal_id)
);

CREATE INDEX idx_tga_transaction ON transaction_goal_allocations (transaction_id);
CREATE INDEX idx_tga_goal ON transaction_goal_allocations (goal_id);