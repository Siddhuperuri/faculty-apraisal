-- 2026-10-13: form clean-up, FDP rule and permanent removal of an account.
--
-- Nothing recorded is changed in meaning: columns are widened or made optional, values already stored are kept.

-- 1. Teaching experience may be up to 1,000 years (the form's limit); the column held at most 999.9 and no more.
ALTER TABLE general_information MODIFY teaching_experience_years DECIMAL(5,1) NULL;

-- 2. A funded project's amount is asked for only when the project is sanctioned; an applied project has none.
--    The column becomes optional. Amounts already saved against an applied project are left as they were (history is not
--    rewritten); the application no longer shows them or counts them, and they go when the record is next saved.
ALTER TABLE funded_projects MODIFY amount DECIMAL(14,2) NULL;

-- 3. Books and chapters: the visible wording of the type. The code stays CHAPTER, so every record keeps working.
UPDATE reference_values SET label = 'Book Chapter' WHERE list_code = 'BOOK_TYPE' AND code = 'CHAPTER';

-- 4. Outreach: "External Examiner" is now "Project External Examiner", and the role is chosen from a dropdown that ends in
--    "Other", where the person then types their own (as for the roles and events of Administration, V37).
--    `role` keeps the wording that is shown and reported (the standard role's wording, or the typed one); role_code holds
--    the choice and role_other the typed text. The old closed "Others" becomes "Other" with the wording it already had.
UPDATE reference_values SET label = 'Project External Examiner' WHERE list_code = 'OUTREACH_ROLE' AND code = 'EXTERNAL_EXAMINER';

ALTER TABLE outreach
  MODIFY role VARCHAR(100) NOT NULL,
  ADD COLUMN role_code VARCHAR(60) NULL,
  ADD COLUMN role_other VARCHAR(80) NULL;

UPDATE outreach SET role_code = 'OTHER', role_other = 'Others', role = 'Others' WHERE role = 'OTHERS';

UPDATE outreach o JOIN reference_values rv
    ON rv.list_code = 'OUTREACH_ROLE' AND rv.code = o.role AND rv.parent_code = ''
   SET o.role_code = o.role, o.role = rv.label
 WHERE o.role_code IS NULL;

-- a role this migration does not know keeps its text and is treated as typed by the person
UPDATE outreach SET role_code = 'OTHER', role_other = LEFT(role, 80) WHERE role_code IS NULL;

UPDATE reference_values SET active = FALSE, locked = FALSE WHERE list_code = 'OUTREACH_ROLE' AND code = 'OTHERS';
INSERT INTO reference_values (list_code, code, parent_code, label, report_label, sort_order, locked) VALUES
  ('OUTREACH_ROLE', 'OTHER', '', 'Other', NULL, 120, TRUE);

-- 5. Removing an account permanently. A person who has taken part in the review (or sent a message, or imported accounts)
--    cannot have their account row deleted, because append-only records point to it. That account is kept as an anonymous
--    stub: deleted_at is set, the e-mail address, name and password are gone, and it appears nowhere. The column is what
--    every list, count, export and sign-in looks at.
ALTER TABLE users ADD COLUMN deleted_at TIMESTAMP NULL;
CREATE INDEX idx_users_deleted ON users (deleted_at);
