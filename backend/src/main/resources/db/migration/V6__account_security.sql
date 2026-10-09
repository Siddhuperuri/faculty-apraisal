-- Account security.
--   must_change_password : set when an administrator creates an account or resets a password; the user may do
--                          nothing but change it until they have.
--   session_version      : bumped on password change or reset; a session carrying an older number is ended.
-- Disabling an account or changing its role is also enforced on every request (see SessionGuardFilter),
-- so these take effect immediately rather than at the next sign-in.

ALTER TABLE users
  ADD COLUMN must_change_password BOOLEAN NOT NULL DEFAULT FALSE,
  ADD COLUMN session_version INT NOT NULL DEFAULT 0,
  ADD COLUMN password_changed_at TIMESTAMP NULL,
  ADD COLUMN last_login_at TIMESTAMP NULL;
