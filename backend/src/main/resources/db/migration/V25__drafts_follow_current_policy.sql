-- Appraisals that are still DRAFTS follow the current scoring policy (version 2, V24) instead of the version they were
-- started under (version 1). Submitted and approved appraisals are not touched, so everything that has been reviewed, and
-- every report already issued, keeps the maxima and breakdown it had.
--
-- For each draft: its policy reference moves to version 2 of the same academic year and cadre, and the maximum marks
-- snapshotted on its score rows are replaced by that version's. Nothing the faculty member entered or scored changes.
-- A draft is left on version 1 if a self-score already saved on it would be above the new maximum of that criterion
-- (the application would then refuse to save the sheet); it is not altered to fit. Each move is written to the audit trail.

CREATE TEMPORARY TABLE draft_v25 AS
SELECT a.id AS appraisal_id, n.id AS new_policy, p.version AS old_version, n.version AS new_version
FROM appraisals a
JOIN scoring_policies p ON p.id = a.scoring_policy_id AND p.version = 1
JOIN scoring_policies n ON n.academic_year_id = p.academic_year_id AND n.cadre_id = p.cadre_id AND n.version = 2 AND n.active = TRUE
WHERE a.status = 'DRAFT'
  AND NOT EXISTS (
    SELECT 1 FROM appraisal_scores s
    JOIN scoring_policy_criteria k ON k.policy_id = n.id AND k.criterion = s.criterion
    WHERE s.appraisal_id = a.id AND k.max_marks > 0 AND s.self_score > k.max_marks);

INSERT INTO audit_logs (actor_id, action, entity_type, entity_id, metadata)
SELECT NULL, 'APPRAISAL_POLICY_MOVED', 'APPRAISAL', appraisal_id,
       JSON_OBJECT('fromVersion', old_version, 'toVersion', new_version)
FROM draft_v25;

UPDATE appraisal_scores s
JOIN draft_v25 d ON d.appraisal_id = s.appraisal_id
JOIN scoring_policy_criteria k ON k.policy_id = d.new_policy AND k.criterion = s.criterion
SET s.max_marks = k.max_marks;

UPDATE appraisals a
JOIN draft_v25 d ON d.appraisal_id = a.id
SET a.scoring_policy_id = d.new_policy, a.updated_at = a.updated_at;

DROP TEMPORARY TABLE draft_v25;
