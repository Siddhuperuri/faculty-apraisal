-- A name and an employee ID for the accounts that have no faculty record (Head of the Department, Principal, administrator),
-- so an account list made from a CSV file can keep them. Faculty keep theirs on faculty_profiles.
ALTER TABLE users
  ADD COLUMN name VARCHAR(120) NULL AFTER email,
  ADD COLUMN employee_id VARCHAR(32) NULL AFTER name,
  ADD CONSTRAINT uq_users_employee_id UNIQUE (employee_id);
