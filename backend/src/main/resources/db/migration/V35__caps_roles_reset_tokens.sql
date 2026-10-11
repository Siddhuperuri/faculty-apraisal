-- Changes of 2026-10-11.
--
-- 1. Scoring policy version 4 (published for every cadre of each open year, copying version 3 exactly except):
--      Funded projects / consultancy (B6)            capped at 10 marks a year
--      Outreach (B8)                                 capped at 10 marks
--      Memberships, awards & recognitions (B9)       capped at 5 marks
--    A cap is the criterion's "maximum" in the policy; 0 still means no cap. The caps are data: an administrator changes
--    them by publishing a new version (Admin > Departments, years & scoring). Submitted and approved appraisals keep the
--    version they began with; drafts move to version 4 (as V25 and V33 did), each move audited.
-- 2. Institutional roles that carry marks in Teaching & Learning: the roles are values of the reference list
--    INSTITUTIONAL_ROLE (managed under Admin > Dropdown lists), the marks of each role belong to a policy version
--    (scoring_policy_role_marks), and a faculty member's roles are institutional_roles_held. A role without a row of
--    marks earns nothing, so a role an administrator adds earns zero until its marks are set.
-- 3. One-time, time-limited password reset links: only a hash of the token is stored.

CREATE TEMPORARY TABLE policy_v35 AS
SELECT p.id AS old_id, p.academic_year_id, p.cadre_id,
       (SELECT MAX(p3.version) FROM scoring_policies p3
        WHERE p3.academic_year_id = p.academic_year_id AND p3.cadre_id = p.cadre_id) + 1 AS new_version
FROM scoring_policies p
JOIN academic_years ay ON ay.id = p.academic_year_id
WHERE ay.active = TRUE AND p.active = TRUE
  AND p.version = (SELECT MAX(p2.version) FROM scoring_policies p2
                   WHERE p2.academic_year_id = p.academic_year_id AND p2.cadre_id = p.cadre_id AND p2.active = TRUE);

INSERT INTO scoring_policies (academic_year_id, cadre_id, version)
SELECT academic_year_id, cadre_id, new_version FROM policy_v35;

INSERT INTO scoring_policy_criteria (policy_id, criterion, max_marks)
SELECT n.id, k.criterion,
       CASE k.criterion WHEN 'FUNDED_PROJECTS' THEN 10 WHEN 'OUTREACH' THEN 10 WHEN 'MEMBERSHIPS_AWARDS' THEN 5 ELSE k.max_marks END
FROM policy_v35 t
JOIN scoring_policies n ON n.academic_year_id = t.academic_year_id AND n.cadre_id = t.cadre_id AND n.version = t.new_version
JOIN scoring_policy_criteria k ON k.policy_id = t.old_id;

INSERT INTO scoring_policy_components (policy_id, criterion, sort_order, description, max_marks)
SELECT n.id, c.criterion, c.sort_order, c.description, c.max_marks
FROM policy_v35 t
JOIN scoring_policies n ON n.academic_year_id = t.academic_year_id AND n.cadre_id = t.cadre_id AND n.version = t.new_version
JOIN scoring_policy_components c ON c.policy_id = t.old_id;

-- the roles and their marks
INSERT INTO reference_lists (code, name, description, parent_list, kind) VALUES
  ('INSTITUTIONAL_ROLE', 'Institutional roles', 'Teaching & Learning: roles held, each earning the marks set for it in the scoring policy', NULL, 'CODE');
INSERT INTO reference_values (list_code, code, parent_code, label, report_label, sort_order, locked) VALUES
  ('INSTITUTIONAL_ROLE', 'HOD', '', 'Head of the Department (HoD)', 'HoD', 10, TRUE),
  ('INSTITUTIONAL_ROLE', 'ASSOCIATE_DEAN', '', 'Associate Dean', NULL, 20, TRUE),
  ('INSTITUTIONAL_ROLE', 'DEAN', '', 'Dean', NULL, 30, TRUE);

CREATE TABLE scoring_policy_role_marks (
  policy_id  BIGINT       NOT NULL,
  role_code  VARCHAR(60)  NOT NULL,
  marks      INT          NOT NULL,
  PRIMARY KEY (policy_id, role_code),
  CONSTRAINT scoring_policy_role_marks_policy_fk FOREIGN KEY (policy_id) REFERENCES scoring_policies (id),
  CONSTRAINT scoring_policy_role_marks_chk CHECK (marks >= 0 AND marks <= 100)
);

-- starting figures for version 4 (placeholders the administrator adjusts): HoD 5, Associate Dean 3; Dean earns nothing until set
INSERT INTO scoring_policy_role_marks (policy_id, role_code, marks)
SELECT n.id, r.role_code, r.marks
FROM policy_v35 t
JOIN scoring_policies n ON n.academic_year_id = t.academic_year_id AND n.cadre_id = t.cadre_id AND n.version = t.new_version
CROSS JOIN (SELECT 'HOD' AS role_code, 5 AS marks UNION ALL SELECT 'ASSOCIATE_DEAN', 3) r;

CREATE TABLE institutional_roles_held (
  id           BIGINT       NOT NULL AUTO_INCREMENT PRIMARY KEY,
  appraisal_id BIGINT       NOT NULL,
  role_code    VARCHAR(60)  NOT NULL,
  remarks      VARCHAR(300) NULL,
  CONSTRAINT institutional_roles_held_appraisal_fk FOREIGN KEY (appraisal_id) REFERENCES appraisals (id) ON DELETE CASCADE,
  CONSTRAINT institutional_roles_held_unique UNIQUE (appraisal_id, role_code)
);

-- drafts follow the new version; what they entered does not change
CREATE TEMPORARY TABLE draft_v35 AS
SELECT a.id AS appraisal_id, n.id AS new_policy, p.version AS old_version, n.version AS new_version
FROM appraisals a
JOIN scoring_policies p ON p.id = a.scoring_policy_id
JOIN policy_v35 t ON t.academic_year_id = p.academic_year_id AND t.cadre_id = p.cadre_id
JOIN scoring_policies n ON n.academic_year_id = t.academic_year_id AND n.cadre_id = t.cadre_id AND n.version = t.new_version
WHERE a.status = 'DRAFT' AND p.version < n.version;

INSERT INTO audit_logs (actor_id, action, entity_type, entity_id, metadata)
SELECT NULL, 'APPRAISAL_POLICY_MOVED', 'APPRAISAL', appraisal_id,
       JSON_OBJECT('fromVersion', old_version, 'toVersion', new_version)
FROM draft_v35;

UPDATE appraisal_scores s
JOIN draft_v35 d ON d.appraisal_id = s.appraisal_id
JOIN scoring_policy_criteria k ON k.policy_id = d.new_policy AND k.criterion = s.criterion
SET s.max_marks = k.max_marks;

UPDATE appraisals a
JOIN draft_v35 d ON d.appraisal_id = a.id
SET a.scoring_policy_id = d.new_policy, a.updated_at = a.updated_at;

DROP TEMPORARY TABLE draft_v35;
DROP TEMPORARY TABLE policy_v35;

-- password reset links: the token itself is never stored, only its SHA-256; a link works once and expires
CREATE TABLE password_reset_tokens (
  id          BIGINT       NOT NULL AUTO_INCREMENT PRIMARY KEY,
  user_id     BIGINT       NOT NULL,
  token_hash  CHAR(64)     NOT NULL,
  expires_at  TIMESTAMP    NOT NULL,
  used_at     TIMESTAMP    NULL,
  requested_from VARCHAR(64) NULL,
  created_at  TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
  CONSTRAINT password_reset_tokens_user_fk FOREIGN KEY (user_id) REFERENCES users (id) ON DELETE CASCADE,
  CONSTRAINT password_reset_tokens_hash_unique UNIQUE (token_hash)
);
CREATE INDEX password_reset_tokens_user_idx ON password_reset_tokens (user_id, created_at);
