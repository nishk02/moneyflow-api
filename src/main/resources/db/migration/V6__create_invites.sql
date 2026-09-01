CREATE TABLE invites (
    id                     TEXT      NOT NULL PRIMARY KEY,
    email                  TEXT      NOT NULL,
    token                  TEXT      NOT NULL UNIQUE,
    status                 TEXT      NOT NULL DEFAULT 'PENDING'
                               CHECK(status IN ('PENDING','AWAITING_OTP','COMPLETED')),
    invited_by             TEXT      NOT NULL REFERENCES users(id),
    expires_at             TIMESTAMP NOT NULL,
    pending_first_name     TEXT,
    pending_last_name      TEXT,
    pending_password_hash  TEXT,
    otp_code_hash          TEXT,
    otp_expires_at         TIMESTAMP,
    otp_attempts           INTEGER   NOT NULL DEFAULT 0,
    created_at             TIMESTAMP NOT NULL,
    updated_at             TIMESTAMP NOT NULL
);