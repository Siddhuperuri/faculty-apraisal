-- A contact number on the account itself, so the Head of the Department and the Principal (who have no faculty profile)
-- can keep one too. Faculty keep theirs on faculty_profiles; the administrator has none.
ALTER TABLE users ADD COLUMN contact_no VARCHAR(20) NULL AFTER email;
