-- Revised scoring components for B2, B3 and B4, the same for every cadre. Published as a NEW policy version; no existing
-- policy, component or appraisal row is changed (see "Versioning" in docs/scoring.md).
--
--   B2 Student Mentoring, Guidance & Achievements  15 = Mentoring 6 + Project guidance 4 + Student achievements 3
--                                                       + Academic / placement / competitive-exam support 2
--   B3 FDPs / Certifications                       15 = FDP / workshops / training 5 + Certification 10
--   B4 Administrative, Curriculum & Quality        15 = Department responsibilities 4 + Institute roles 3
--                                                       + Curriculum / BoS 3 + Accreditation works 5
--
-- "Quality / accreditation" (3) and "Measurable institutional contribution" (2) are no longer in the current policy;
-- they stay on the older versions, so appraisals already started keep the breakdown they began with. The Certification
-- maximum is a cap on the component, not a rate per entry. B1 and B5 to B9 are copied unchanged.
--
-- Which policies: for every academic year that is OPEN, each cadre's latest active version (the one a new appraisal would
-- use) is followed by a new version with the figures above. Closed years are left exactly as they were. A year opened
-- later copies the latest policies of the most recent year, so it starts from these figures.

CREATE TEMPORARY TABLE policy_v24 AS
SELECT p.id AS old_id, p.academic_year_id, p.cadre_id,
       (SELECT MAX(p3.version) FROM scoring_policies p3
        WHERE p3.academic_year_id = p.academic_year_id AND p3.cadre_id = p.cadre_id) + 1 AS new_version
FROM scoring_policies p
JOIN academic_years ay ON ay.id = p.academic_year_id
WHERE ay.active = TRUE AND p.active = TRUE
  AND p.version = (SELECT MAX(p2.version) FROM scoring_policies p2
                   WHERE p2.academic_year_id = p.academic_year_id AND p2.cadre_id = p.cadre_id AND p2.active = TRUE);

INSERT INTO scoring_policies (academic_year_id, cadre_id, version)
SELECT academic_year_id, cadre_id, new_version FROM policy_v24;

-- the maxima: B2, B3 and B4 become 15, everything else is carried over
INSERT INTO scoring_policy_criteria (policy_id, criterion, max_marks)
SELECT n.id, k.criterion,
       CASE WHEN k.criterion IN ('STUDENT_MENTORING', 'FDP_CERTIFICATIONS', 'ADMINISTRATIVE') THEN 15 ELSE k.max_marks END
FROM policy_v24 t
JOIN scoring_policies n ON n.academic_year_id = t.academic_year_id AND n.cadre_id = t.cadre_id AND n.version = t.new_version
JOIN scoring_policy_criteria k ON k.policy_id = t.old_id;

-- the components of the criteria that do not change (B1)
INSERT INTO scoring_policy_components (policy_id, criterion, sort_order, description, max_marks)
SELECT n.id, c.criterion, c.sort_order, c.description, c.max_marks
FROM policy_v24 t
JOIN scoring_policies n ON n.academic_year_id = t.academic_year_id AND n.cadre_id = t.cadre_id AND n.version = t.new_version
JOIN scoring_policy_components c ON c.policy_id = t.old_id
WHERE c.criterion NOT IN ('STUDENT_MENTORING', 'FDP_CERTIFICATIONS', 'ADMINISTRATIVE');

-- the new components of B2, B3 and B4
INSERT INTO scoring_policy_components (policy_id, criterion, sort_order, description, max_marks)
SELECT n.id, m.criterion, m.sort_order, m.description, m.marks
FROM policy_v24 t
JOIN scoring_policies n ON n.academic_year_id = t.academic_year_id AND n.cadre_id = t.cadre_id AND n.version = t.new_version
CROSS JOIN (
  SELECT 'STUDENT_MENTORING' criterion, 1 sort_order, 'Mentoring' description, 6 marks UNION ALL
  SELECT 'STUDENT_MENTORING', 2, 'Project guidance', 4 UNION ALL
  SELECT 'STUDENT_MENTORING', 3, 'Student achievements', 3 UNION ALL
  SELECT 'STUDENT_MENTORING', 4, 'Academic / placement / competitive-exam support', 2 UNION ALL
  SELECT 'FDP_CERTIFICATIONS', 1, 'FDP / workshops / training', 5 UNION ALL
  SELECT 'FDP_CERTIFICATIONS', 2, 'Certification', 10 UNION ALL
  SELECT 'ADMINISTRATIVE', 1, 'Department responsibilities', 4 UNION ALL
  SELECT 'ADMINISTRATIVE', 2, 'Institute roles', 3 UNION ALL
  SELECT 'ADMINISTRATIVE', 3, 'Curriculum / BoS', 3 UNION ALL
  SELECT 'ADMINISTRATIVE', 4, 'Accreditation works', 5
) m;

DROP TEMPORARY TABLE policy_v24;
