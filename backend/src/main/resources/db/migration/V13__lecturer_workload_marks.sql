-- Lecturer, B1 Teaching & Learning: "pass/result performance" (10) is dropped and its marks go to
-- "Workload & course delivery", which becomes 20. The criterion's maximum (40) does not change, and the components
-- still add up to it. The workload component is also what the application now awards automatically, 5 marks per course
-- handled up to its maximum, so 20 marks need 4 courses.

DELETE FROM scoring_policy_components
WHERE criterion = 'TEACHING_LEARNING' AND description = 'pass/result performance'
  AND policy_id IN (SELECT p.id FROM scoring_policies p JOIN cadres c ON c.id = p.cadre_id WHERE c.code = 'LECTURER');

UPDATE scoring_policy_components SET max_marks = 20
WHERE criterion = 'TEACHING_LEARNING' AND description = 'Workload & course delivery'
  AND policy_id IN (SELECT p.id FROM scoring_policies p JOIN cadres c ON c.id = p.cadre_id WHERE c.code = 'LECTURER');

-- close the gap left at position 2, via a detour so the unique (policy, criterion, position) is never violated
UPDATE scoring_policy_components SET sort_order = sort_order + 100
WHERE criterion = 'TEACHING_LEARNING' AND sort_order > 2
  AND policy_id IN (SELECT p.id FROM scoring_policies p JOIN cadres c ON c.id = p.cadre_id WHERE c.code = 'LECTURER');
UPDATE scoring_policy_components SET sort_order = sort_order - 101
WHERE criterion = 'TEACHING_LEARNING' AND sort_order > 100
  AND policy_id IN (SELECT p.id FROM scoring_policies p JOIN cadres c ON c.id = p.cadre_id WHERE c.code = 'LECTURER');
