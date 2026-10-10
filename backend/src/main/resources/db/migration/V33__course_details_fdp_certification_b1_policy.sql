-- Form and scoring changes of 2026-10-10.
--
-- 1. Courses handled: each course names the faculty member's role in it (course coordinator or course instructor) and its
--    section (A to E). Courses entered before this have neither, so both columns allow NULL; the application requires
--    them whenever a course is saved, so an old course asks for them the next time it is edited.
ALTER TABLE teaching_courses
    ADD COLUMN course_role VARCHAR(12) NULL,
    ADD COLUMN class_section CHAR(1) NULL,
    ADD CONSTRAINT teaching_courses_course_role_chk CHECK (course_role IN ('COORDINATOR', 'INSTRUCTOR')),
    ADD CONSTRAINT teaching_courses_class_section_chk CHECK (class_section IN ('A', 'B', 'C', 'D', 'E'));

-- 2. Workshops / FDPs / seminars / training programs: the duration is a number of days; start and end dates are no longer
--    asked for. The dates already entered are kept (the columns only stop being required), nothing is deleted.
ALTER TABLE fdps MODIFY start_date DATE NULL, MODIFY end_date DATE NULL;

-- 3. Certifications: no dates; a duration in whole weeks (hours are no longer accepted); a platform name when the platform
--    is "Other". The old free-text duration stays on old rows. Where it holds only a number of weeks ("12" or "12 weeks")
--    it becomes the new duration; a duration given in hours cannot be turned into weeks, so that row asks for the
--    duration the next time it is edited.
ALTER TABLE certifications
    MODIFY start_date DATE NULL, MODIFY end_date DATE NULL,
    ADD COLUMN duration_weeks SMALLINT NULL,
    ADD COLUMN platform_other VARCHAR(100) NULL,
    ADD CONSTRAINT certifications_duration_weeks_chk CHECK (duration_weeks BETWEEN 1 AND 520);

UPDATE certifications
SET duration_weeks = CAST(REGEXP_SUBSTR(duration_weeks_hours, '[0-9]+') AS UNSIGNED)
WHERE duration_weeks_hours REGEXP '^ *[0-9]{1,3} *(weeks?|wks?)? *$'
  AND CAST(REGEXP_SUBSTR(duration_weeks_hours, '[0-9]+') AS UNSIGNED) BETWEEN 1 AND 520;

-- 4. Teaching & Learning components: "student feedback" is gone and the parts are re-divided (see docs/scoring.md):
--      Lecturer, Assistant Professor        Workload 20 + Course file / assessment 10 + Innovative practices 10 = 40
--      Senior Assistant Professor           Workload 20 + Course file / assessment 10 + Innovative practices  5 = 35
--      Associate Professor, Professor       Workload 15 + Course file / assessment 10 + Innovative practices  5 = 30
--    Published as a NEW policy version for every cadre of each open academic year (as V24 did); no existing policy,
--    component or appraisal row is changed, so submitted and approved appraisals keep the version they began with.
--    Drafts follow the new version (as V25 did).
CREATE TEMPORARY TABLE policy_v33 AS
SELECT p.id AS old_id, p.academic_year_id, p.cadre_id,
       (SELECT MAX(p3.version) FROM scoring_policies p3
        WHERE p3.academic_year_id = p.academic_year_id AND p3.cadre_id = p.cadre_id) + 1 AS new_version
FROM scoring_policies p
JOIN academic_years ay ON ay.id = p.academic_year_id
WHERE ay.active = TRUE AND p.active = TRUE
  AND p.version = (SELECT MAX(p2.version) FROM scoring_policies p2
                   WHERE p2.academic_year_id = p.academic_year_id AND p2.cadre_id = p.cadre_id AND p2.active = TRUE);

INSERT INTO scoring_policies (academic_year_id, cadre_id, version)
SELECT academic_year_id, cadre_id, new_version FROM policy_v33;

-- every maximum is carried over unchanged (B1 keeps 40 / 35 / 30)
INSERT INTO scoring_policy_criteria (policy_id, criterion, max_marks)
SELECT n.id, k.criterion, k.max_marks
FROM policy_v33 t
JOIN scoring_policies n ON n.academic_year_id = t.academic_year_id AND n.cadre_id = t.cadre_id AND n.version = t.new_version
JOIN scoring_policy_criteria k ON k.policy_id = t.old_id;

-- the components of every criterion except B1 are carried over unchanged
INSERT INTO scoring_policy_components (policy_id, criterion, sort_order, description, max_marks)
SELECT n.id, c.criterion, c.sort_order, c.description, c.max_marks
FROM policy_v33 t
JOIN scoring_policies n ON n.academic_year_id = t.academic_year_id AND n.cadre_id = t.cadre_id AND n.version = t.new_version
JOIN scoring_policy_components c ON c.policy_id = t.old_id
WHERE c.criterion <> 'TEACHING_LEARNING';

-- the new components of B1
INSERT INTO scoring_policy_components (policy_id, criterion, sort_order, description, max_marks)
SELECT n.id, 'TEACHING_LEARNING', m.sort_order, m.description, m.marks
FROM policy_v33 t
JOIN scoring_policies n ON n.academic_year_id = t.academic_year_id AND n.cadre_id = t.cadre_id AND n.version = t.new_version
JOIN cadres cd ON cd.id = t.cadre_id
JOIN (
  SELECT 1 sort_order, 'Workload & course delivery' description, 20 marks, 'LECTURER' cadre UNION ALL
  SELECT 2, 'course-file/assessment quality', 10, 'LECTURER' UNION ALL
  SELECT 3, 'innovative/remedial/advanced-learning practices', 10, 'LECTURER' UNION ALL
  SELECT 1, 'Workload & course delivery', 20, 'ASST_PROF' UNION ALL
  SELECT 2, 'course-file/assessment quality', 10, 'ASST_PROF' UNION ALL
  SELECT 3, 'innovative/remedial/advanced-learning practices', 10, 'ASST_PROF' UNION ALL
  SELECT 1, 'Workload & course delivery', 20, 'SR_ASST_PROF' UNION ALL
  SELECT 2, 'course-file/assessment quality', 10, 'SR_ASST_PROF' UNION ALL
  SELECT 3, 'innovative/remedial/advanced-learning practices', 5, 'SR_ASST_PROF' UNION ALL
  SELECT 1, 'Workload & course delivery', 15, 'ASSOC_PROF' UNION ALL
  SELECT 2, 'course-file/assessment quality', 10, 'ASSOC_PROF' UNION ALL
  SELECT 3, 'innovative/remedial/advanced-learning practices', 5, 'ASSOC_PROF' UNION ALL
  SELECT 1, 'Workload & course delivery', 15, 'PROFESSOR' UNION ALL
  SELECT 2, 'course-file/assessment quality', 10, 'PROFESSOR' UNION ALL
  SELECT 3, 'innovative/remedial/advanced-learning practices', 5, 'PROFESSOR'
) m ON m.cadre = cd.code;

-- drafts move to the new version; what they entered does not change. Each move is written to the audit trail.
CREATE TEMPORARY TABLE draft_v33 AS
SELECT a.id AS appraisal_id, n.id AS new_policy, p.version AS old_version, n.version AS new_version
FROM appraisals a
JOIN scoring_policies p ON p.id = a.scoring_policy_id
JOIN policy_v33 t ON t.academic_year_id = p.academic_year_id AND t.cadre_id = p.cadre_id
JOIN scoring_policies n ON n.academic_year_id = t.academic_year_id AND n.cadre_id = t.cadre_id AND n.version = t.new_version
WHERE a.status = 'DRAFT' AND p.version < n.version;

INSERT INTO audit_logs (actor_id, action, entity_type, entity_id, metadata)
SELECT NULL, 'APPRAISAL_POLICY_MOVED', 'APPRAISAL', appraisal_id,
       JSON_OBJECT('fromVersion', old_version, 'toVersion', new_version)
FROM draft_v33;

UPDATE appraisal_scores s
JOIN draft_v33 d ON d.appraisal_id = s.appraisal_id
JOIN scoring_policy_criteria k ON k.policy_id = d.new_policy AND k.criterion = s.criterion
SET s.max_marks = k.max_marks;

UPDATE appraisals a
JOIN draft_v33 d ON d.appraisal_id = a.id
SET a.scoring_policy_id = d.new_policy, a.updated_at = a.updated_at;

DROP TEMPORARY TABLE draft_v33;
DROP TEMPORARY TABLE policy_v33;
