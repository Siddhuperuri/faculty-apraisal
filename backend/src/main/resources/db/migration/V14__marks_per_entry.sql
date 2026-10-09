-- New scoring rules.
--
-- B1 Teaching & Learning has a new maximum for each cadre, and the components below add up to it. The first component,
-- workload and course delivery, is 20 for everyone and is awarded by the application: 5 marks for each course handled.
--   Lecturer 40, Assistant Professor 40, Senior Assistant Professor 35, Associate Professor 30, Professor 30.
--
-- B5 to B9 (research and publications, funded projects, patents / books, outreach, memberships) no longer have a
-- maximum. They are marked per entry, the same for every cadre, and the application adds the marks up (ScoringRules).
-- Their policy rows are kept at 0 and ignored. The components of B5 are gone with the maximum.
--
-- B2 to B4 keep their maxima and components.

UPDATE scoring_policy_criteria k
JOIN scoring_policies p ON p.id = k.policy_id
JOIN cadres c ON c.id = p.cadre_id
SET k.max_marks = CASE c.code WHEN 'LECTURER' THEN 40 WHEN 'ASST_PROF' THEN 40 WHEN 'SR_ASST_PROF' THEN 35 ELSE 30 END
WHERE k.criterion = 'TEACHING_LEARNING';

DELETE FROM scoring_policy_components WHERE criterion IN ('TEACHING_LEARNING', 'RESEARCH_PUBLICATIONS');

INSERT INTO scoring_policy_components (policy_id, criterion, sort_order, description, max_marks)
SELECT k.policy_id, k.criterion, m.sort_order, m.description,
       CASE WHEN m.sort_order = 1 THEN 20
            WHEN c.code IN ('LECTURER', 'ASST_PROF') THEN m.big
            WHEN c.code = 'SR_ASST_PROF' THEN m.mid
            ELSE m.small END
FROM scoring_policy_criteria k
JOIN scoring_policies p ON p.id = k.policy_id
JOIN cadres c ON c.id = p.cadre_id
JOIN (
  SELECT 1 sort_order, 'Workload & course delivery' description, 20 big, 20 mid, 20 small UNION ALL
  SELECT 2, 'student feedback', 8, 6, 4 UNION ALL
  SELECT 3, 'course-file/assessment quality', 6, 5, 3 UNION ALL
  SELECT 4, 'innovative/remedial/advanced-learning practices', 6, 4, 3
) m
WHERE k.criterion = 'TEACHING_LEARNING';

UPDATE scoring_policy_criteria SET max_marks = 0
WHERE criterion IN ('RESEARCH_PUBLICATIONS', 'FUNDED_PROJECTS', 'PATENTS_BOOKS_IPR', 'OUTREACH', 'MEMBERSHIPS_AWARDS');

-- A score may now be above the (absent) maximum of a per-entry criterion; the application checks B1 to B4 against theirs.
ALTER TABLE appraisal_scores DROP CHECK appraisal_scores_chk_1;
ALTER TABLE appraisal_scores DROP CHECK appraisal_scores_chk_2;
ALTER TABLE appraisal_scores MODIFY self_score DECIMAL(9,2) NULL, MODIFY review_score DECIMAL(9,2) NULL;
ALTER TABLE appraisal_scores ADD CONSTRAINT appraisal_scores_self_nonneg CHECK (self_score IS NULL OR self_score >= 0);
ALTER TABLE appraisal_scores ADD CONSTRAINT appraisal_scores_review_nonneg CHECK (review_score IS NULL OR review_score >= 0);
