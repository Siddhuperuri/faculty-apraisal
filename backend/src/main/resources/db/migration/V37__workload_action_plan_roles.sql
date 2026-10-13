-- Changes of 2026-10-13 (see docs/decisions.md, ADR-041).
--
-- 1. Scoring policy version 5, published for every cadre of each open year (as V24, V33 and V35 did). It copies version 4
--    except:
--      B1 Teaching & Learning   workload & course delivery is 20 for every cadre (it was 15 for Associate Professor and
--                               Professor), so B1 is 40 / 40 / 35 / 35 / 35 for Lecturer, Assistant Professor, Senior
--                               Assistant Professor, Associate Professor and Professor
--      B6 Funded projects       no limit on the criterion: a sanctioned project earns 50 each and the applied ones are
--                               limited to 10 marks a year by the scoring rules (it was a limit of 10 on the whole criterion)
--    scoring_policies.courses_per_year is the number of courses a year that earns the whole workload part for that
--    cadre (8, 8, 8, 4, 2); each course earns the workload maximum divided by it (2.5, 2.5, 2.5, 5, 10 marks). Older
--    versions keep NULL, which is the earlier rule (an eighth of each part for each course, the workload rounded to a
--    whole mark), so submitted and approved appraisals keep exactly the marks they had. Drafts move to version 5, each
--    move written to the audit trail.
ALTER TABLE scoring_policies ADD COLUMN courses_per_year TINYINT NULL;

CREATE TEMPORARY TABLE policy_v37 AS
SELECT p.id AS old_id, p.academic_year_id, p.cadre_id,
       (SELECT MAX(p3.version) FROM scoring_policies p3
        WHERE p3.academic_year_id = p.academic_year_id AND p3.cadre_id = p.cadre_id) + 1 AS new_version
FROM scoring_policies p
JOIN academic_years ay ON ay.id = p.academic_year_id
WHERE ay.active = TRUE AND p.active = TRUE
  AND p.version = (SELECT MAX(p2.version) FROM scoring_policies p2
                   WHERE p2.academic_year_id = p.academic_year_id AND p2.cadre_id = p.cadre_id AND p2.active = TRUE);

INSERT INTO scoring_policies (academic_year_id, cadre_id, version, courses_per_year)
SELECT t.academic_year_id, t.cadre_id, t.new_version,
       CASE cd.code WHEN 'ASSOC_PROF' THEN 4 WHEN 'PROFESSOR' THEN 2 ELSE 8 END
FROM policy_v37 t JOIN cadres cd ON cd.id = t.cadre_id;

INSERT INTO scoring_policy_criteria (policy_id, criterion, max_marks)
SELECT n.id, k.criterion,
       CASE
         WHEN k.criterion = 'FUNDED_PROJECTS' THEN 0
         WHEN k.criterion = 'TEACHING_LEARNING' THEN CASE cd.code WHEN 'LECTURER' THEN 40 WHEN 'ASST_PROF' THEN 40 ELSE 35 END
         ELSE k.max_marks
       END
FROM policy_v37 t
JOIN scoring_policies n ON n.academic_year_id = t.academic_year_id AND n.cadre_id = t.cadre_id AND n.version = t.new_version
JOIN cadres cd ON cd.id = t.cadre_id
JOIN scoring_policy_criteria k ON k.policy_id = t.old_id;

-- every component except B1's is carried over unchanged
INSERT INTO scoring_policy_components (policy_id, criterion, sort_order, description, max_marks)
SELECT n.id, c.criterion, c.sort_order, c.description, c.max_marks
FROM policy_v37 t
JOIN scoring_policies n ON n.academic_year_id = t.academic_year_id AND n.cadre_id = t.cadre_id AND n.version = t.new_version
JOIN scoring_policy_components c ON c.policy_id = t.old_id
WHERE c.criterion <> 'TEACHING_LEARNING';

-- B1: workload 20; course file / assessment quality 10 (4 when the faculty member is only ever a course instructor, which
-- the scoring rules apply); innovative practices 10 for Lecturer and Assistant Professor, 5 for the others
INSERT INTO scoring_policy_components (policy_id, criterion, sort_order, description, max_marks)
SELECT n.id, 'TEACHING_LEARNING', m.sort_order, m.description,
       CASE WHEN m.sort_order = 3 AND cd.code IN ('LECTURER', 'ASST_PROF') THEN 10 ELSE m.marks END
FROM policy_v37 t
JOIN scoring_policies n ON n.academic_year_id = t.academic_year_id AND n.cadre_id = t.cadre_id AND n.version = t.new_version
JOIN cadres cd ON cd.id = t.cadre_id
CROSS JOIN (
  SELECT 1 sort_order, 'Workload & course delivery' description, 20 marks UNION ALL
  SELECT 2, 'course-file/assessment quality', 10 UNION ALL
  SELECT 3, 'innovative/remedial/advanced-learning practices', 5
) m;

INSERT INTO scoring_policy_role_marks (policy_id, role_code, marks)
SELECT n.id, r.role_code, r.marks
FROM policy_v37 t
JOIN scoring_policies n ON n.academic_year_id = t.academic_year_id AND n.cadre_id = t.cadre_id AND n.version = t.new_version
JOIN scoring_policy_role_marks r ON r.policy_id = t.old_id;

-- drafts follow the new version; what they entered does not change
CREATE TEMPORARY TABLE draft_v37 AS
SELECT a.id AS appraisal_id, n.id AS new_policy, p.version AS old_version, n.version AS new_version
FROM appraisals a
JOIN scoring_policies p ON p.id = a.scoring_policy_id
JOIN policy_v37 t ON t.academic_year_id = p.academic_year_id AND t.cadre_id = p.cadre_id
JOIN scoring_policies n ON n.academic_year_id = t.academic_year_id AND n.cadre_id = t.cadre_id AND n.version = t.new_version
WHERE a.status = 'DRAFT' AND p.version < n.version;

INSERT INTO audit_logs (actor_id, action, entity_type, entity_id, metadata)
SELECT NULL, 'APPRAISAL_POLICY_MOVED', 'APPRAISAL', appraisal_id,
       JSON_OBJECT('fromVersion', old_version, 'toVersion', new_version)
FROM draft_v37;

UPDATE appraisal_scores s
JOIN draft_v37 d ON d.appraisal_id = s.appraisal_id
JOIN scoring_policy_criteria k ON k.policy_id = d.new_policy AND k.criterion = s.criterion
SET s.max_marks = k.max_marks;

UPDATE appraisals a
JOIN draft_v37 d ON d.appraisal_id = a.id
SET a.scoring_policy_id = d.new_policy, a.updated_at = a.updated_at;

DROP TEMPORARY TABLE draft_v37;
DROP TEMPORARY TABLE policy_v37;

-- 2. Semesters: 1 to 8 for every department. The values 9 to 12 are switched off (a course already saved with one keeps it).
UPDATE reference_values SET active = FALSE, locked = FALSE WHERE list_code = 'SEMESTER' AND CAST(code AS UNSIGNED) > 8;

-- 3. Section 12, Action Plan for the Next 2 Years: one text of at most 2,000 words (checked by the application). It may be
--    empty in a draft; the application requires it before the appraisal is submitted.
CREATE TABLE action_plan (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  appraisal_id BIGINT NOT NULL UNIQUE,
  plan_text MEDIUMTEXT NULL,
  FOREIGN KEY (appraisal_id) REFERENCES appraisals(id) ON DELETE CASCADE
);

-- 4. Institute / department roles and the type and role of an event are chosen from a dropdown that ends in "Other",
--    where the person then types their own. `role` / `activity_type` keep the wording that is shown and reported (the
--    standard role's wording, or the typed one); role_code / activity_code hold the choice and *_other the typed text.
--    Every row saved before this becomes "Other" with the wording it already had, so nothing is lost or changed.
ALTER TABLE administrative_roles
  ADD COLUMN role_code VARCHAR(60) NULL,
  ADD COLUMN role_other VARCHAR(160) NULL;
UPDATE administrative_roles SET role_code = 'OTHER', role_other = role;

ALTER TABLE events
  ADD COLUMN activity_code VARCHAR(60) NULL,
  ADD COLUMN activity_other VARCHAR(80) NULL,
  ADD COLUMN role_code VARCHAR(60) NULL,
  ADD COLUMN role_other VARCHAR(80) NULL;
UPDATE events SET activity_code = 'OTHER', activity_other = activity_type, role_code = 'OTHER', role_other = role;

INSERT INTO reference_lists (code, name, description, parent_list, kind) VALUES
  ('ROLE_SCOPE', 'Level of a role', 'Administration: institute-level or department-level (the roles below belong to one of them)', NULL, 'CODE'),
  ('ADMIN_ROLE', 'Institute and department roles', 'Administration: the roles offered for each level; "Other" is always last and lets the person type their own', 'ROLE_SCOPE', 'CODE'),
  ('EVENT_ACTIVITY', 'Type of event', 'Administration: events organized or coordinated; "Other" is always last', NULL, 'CODE'),
  ('EVENT_ROLE', 'Role in an event', 'Administration: events organized or coordinated; "Other" is always last', NULL, 'CODE');

INSERT INTO reference_values (list_code, code, parent_code, label, report_label, sort_order, locked) VALUES
  ('ROLE_SCOPE', 'INSTITUTE', '', 'Institute level', NULL, 10, TRUE),
  ('ROLE_SCOPE', 'DEPARTMENT', '', 'Department level', NULL, 20, TRUE),

  ('ADMIN_ROLE', 'NAAC_NBA_COORDINATOR', 'INSTITUTE', 'NAAC / NBA criterion coordinator', NULL, 10, FALSE),
  ('ADMIN_ROLE', 'IQAC', 'INSTITUTE', 'IQAC', NULL, 20, FALSE),
  ('ADMIN_ROLE', 'EXAM_CELL', 'INSTITUTE', 'Exam Cell', NULL, 30, FALSE),
  ('ADMIN_ROLE', 'TRAINING_PLACEMENT', 'INSTITUTE', 'Training & Placement', NULL, 40, FALSE),
  ('ADMIN_ROLE', 'NSS_NCC', 'INSTITUTE', 'NSS / NCC', NULL, 50, FALSE),
  ('ADMIN_ROLE', 'ANTI_RAGGING', 'INSTITUTE', 'Anti-Ragging', NULL, 60, FALSE),
  ('ADMIN_ROLE', 'OTHER', 'INSTITUTE', 'Other', NULL, 70, TRUE),

  ('ADMIN_ROLE', 'CLASS_COORDINATOR', 'DEPARTMENT', 'Class Coordinator', NULL, 80, FALSE),
  ('ADMIN_ROLE', 'MODULE_COORDINATOR', 'DEPARTMENT', 'Module Coordinator', NULL, 90, FALSE),
  ('ADMIN_ROLE', 'LAB_INCHARGE', 'DEPARTMENT', 'Lab In-charge', NULL, 100, FALSE),
  ('ADMIN_ROLE', 'TIMETABLE_INCHARGE', 'DEPARTMENT', 'Time-table In-charge', NULL, 110, FALSE),
  ('ADMIN_ROLE', 'NAAC_NBA_COORDINATOR', 'DEPARTMENT', 'NAAC / NBA criterion coordinator', NULL, 120, FALSE),
  ('ADMIN_ROLE', 'IQAC', 'DEPARTMENT', 'IQAC', NULL, 130, FALSE),
  ('ADMIN_ROLE', 'EXAM_CELL', 'DEPARTMENT', 'Exam Cell', NULL, 140, FALSE),
  ('ADMIN_ROLE', 'TRAINING_PLACEMENT', 'DEPARTMENT', 'Training & Placement', NULL, 150, FALSE),
  ('ADMIN_ROLE', 'NSS_NCC', 'DEPARTMENT', 'NSS / NCC', NULL, 160, FALSE),
  ('ADMIN_ROLE', 'ANTI_RAGGING', 'DEPARTMENT', 'Anti-Ragging', NULL, 170, FALSE),
  ('ADMIN_ROLE', 'OTHER', 'DEPARTMENT', 'Other', NULL, 180, TRUE),

  ('EVENT_ACTIVITY', 'WORKSHOP', '', 'Workshop', NULL, 10, FALSE),
  ('EVENT_ACTIVITY', 'SEMINAR', '', 'Seminar', NULL, 20, FALSE),
  ('EVENT_ACTIVITY', 'GUEST_LECTURE', '', 'Guest lecture', NULL, 30, FALSE),
  ('EVENT_ACTIVITY', 'TRAINING_PROGRAM', '', 'Training program', NULL, 40, FALSE),
  ('EVENT_ACTIVITY', 'INDUSTRIAL_VISIT', '', 'Industrial visit / tour', NULL, 50, FALSE),
  ('EVENT_ACTIVITY', 'COMPETITION', '', 'Competition / hackathon', NULL, 60, FALSE),
  ('EVENT_ACTIVITY', 'CONFERENCE', '', 'Conference / symposium', NULL, 70, FALSE),
  ('EVENT_ACTIVITY', 'OTHER', '', 'Other', NULL, 80, TRUE),

  ('EVENT_ROLE', 'CONVENER', '', 'Convener', NULL, 10, FALSE),
  ('EVENT_ROLE', 'CO_CONVENER', '', 'Co-convener', NULL, 20, FALSE),
  ('EVENT_ROLE', 'COORDINATOR', '', 'Coordinator', NULL, 30, FALSE),
  ('EVENT_ROLE', 'CO_COORDINATOR', '', 'Co-coordinator', NULL, 40, FALSE),
  ('EVENT_ROLE', 'ORGANIZING_MEMBER', '', 'Organizing member', NULL, 50, FALSE),
  ('EVENT_ROLE', 'OTHER', '', 'Other', NULL, 60, TRUE);

-- the old suggestion lists are no longer offered anywhere; they are kept (nothing is deleted) and said to be replaced
UPDATE reference_lists SET description = CONCAT('Replaced by "Institute and department roles"; no longer used. ', description)
WHERE code IN ('INSTITUTE_ROLE_SUGGESTION', 'DEPARTMENT_ROLE_SUGGESTION');
