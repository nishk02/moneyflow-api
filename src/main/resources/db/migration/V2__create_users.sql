CREATE TABLE users
(
    id              TEXT        NOT NULL PRIMARY KEY,
    first_name      TEXT        NOT NULL,
    last_name       TEXT        NOT NULL,
    email           TEXT        NOT NULL UNIQUE,
    password_hash   TEXT        NOT NULL,
    onboarding_step INTEGER     NOT NULL DEFAULT 0,
    created_at      TIMESTAMP   NOT NULL,
    updated_at      TIMESTAMP   NOT NULL
);

CREATE INDEX idx_users_email ON users (email);