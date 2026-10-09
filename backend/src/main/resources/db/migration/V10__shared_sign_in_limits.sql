-- Sign-in limits kept in the database instead of in each server's memory, so every instance of the backend counts in
-- the same place and a restart forgets nothing.
--
--   sign_in_attempts          one row per limiter key (account + address, account, address, or a password change).
--                             Only the SHA-256 of the key is stored: no e-mail address or client address appears here.
--                             window_start_ms is epoch milliseconds; a row is dead ten minutes after it and is purged.
--   users.last_login_address  where the account last signed in successfully. That address is exempt from the
--                             per-account limit, so the owner is not shut out by other people's guessing.

CREATE TABLE sign_in_attempts (
  key_hash CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL PRIMARY KEY,
  attempts INT NOT NULL,
  window_start_ms BIGINT NOT NULL,
  refused BOOLEAN NOT NULL DEFAULT FALSE,     -- already refused once in this window (so it is logged once)
  INDEX idx_sign_in_attempts_window (window_start_ms),
  CHECK (attempts >= 0)
);

ALTER TABLE users ADD COLUMN last_login_address VARCHAR(64) NULL;
