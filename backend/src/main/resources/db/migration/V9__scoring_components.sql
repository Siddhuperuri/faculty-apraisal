-- Scoring components: how the maximum of a criterion is made up, per cadre.
--
-- Source: the college's "Cadre_wise" document, "Scoring criteria B1 to B5 (Annexure A & B)". It gives, for each cadre,
-- the maximum marks of criteria B1 to B5 and the components those marks are made of. The component descriptions and
-- marks below are that document's, word for word (generated from it, not retyped).
--
--   Cadre                        B1  B2  B3  B4  B5   B1-B5
--   Lecturer                     40  15  15  15   5      90
--   Assistant Professor          30  12  10  15  15      82
--   Senior Assistant Professor   25  10   8  15  20      78
--   Associate Professor          20   8   5  15  25      73
--   Professor                    15   5   5  20  25      70
--
-- Those maxima are the ones already in every seeded policy (V2), so no mark changes and nothing is rescaled. The document
-- stops at B5: the remaining criteria of the form's Annexure A (funded projects, patents / books / IPR, outreach,
-- memberships / awards) keep their maxima and have no component breakdown. They are what brings each cadre to 100.
--
-- Components belong to a policy version, like the maxima, so an appraisal keeps the breakdown it started with.
-- A criterion's components always add up to its maximum: they are attached only where the policy's maximum is the
-- document's, and the application keeps that true when a new version is published.

CREATE TABLE scoring_policy_components (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  policy_id BIGINT NOT NULL,
  criterion VARCHAR(48) NOT NULL,
  sort_order INT NOT NULL,                    -- the order the document lists them in
  description VARCHAR(160) NOT NULL,
  max_marks INT NOT NULL,
  UNIQUE (policy_id, criterion, sort_order),
  CHECK (sort_order >= 1),
  CHECK (max_marks >= 1),
  FOREIGN KEY (policy_id, criterion) REFERENCES scoring_policy_criteria (policy_id, criterion)
);

INSERT INTO scoring_policy_components (policy_id, criterion, sort_order, description, max_marks)
SELECT k.policy_id, k.criterion, m.sort_order, m.description, m.marks
FROM scoring_policy_criteria k
JOIN scoring_policies p ON p.id = k.policy_id
JOIN cadres c ON c.id = p.cadre_id
JOIN (
  SELECT 'LECTURER' cadre, 'TEACHING_LEARNING' criterion, 40 criterion_max, 1 sort_order, 'Workload & course delivery' description, 10 marks UNION ALL
  SELECT 'LECTURER', 'TEACHING_LEARNING', 40, 2, 'pass/result performance', 10 UNION ALL
  SELECT 'LECTURER', 'TEACHING_LEARNING', 40, 3, 'student feedback', 8 UNION ALL
  SELECT 'LECTURER', 'TEACHING_LEARNING', 40, 4, 'course-file/assessment quality', 6 UNION ALL
  SELECT 'LECTURER', 'TEACHING_LEARNING', 40, 5, 'innovative/remedial/advanced-learning practices', 6 UNION ALL
  SELECT 'LECTURER', 'STUDENT_MENTORING', 15, 1, 'Mentoring records & attendance/academic follow-up', 5 UNION ALL
  SELECT 'LECTURER', 'STUDENT_MENTORING', 15, 2, 'project/lab guidance', 4 UNION ALL
  SELECT 'LECTURER', 'STUDENT_MENTORING', 15, 3, 'student achievement support', 4 UNION ALL
  SELECT 'LECTURER', 'STUDENT_MENTORING', 15, 4, 'parent/student support evidence', 2 UNION ALL
  SELECT 'LECTURER', 'FDP_CERTIFICATIONS', 15, 1, 'FDP/workshops/training', 10 UNION ALL
  SELECT 'LECTURER', 'FDP_CERTIFICATIONS', 15, 2, 'relevant certification', 5 UNION ALL
  SELECT 'LECTURER', 'ADMINISTRATIVE', 15, 1, 'Department responsibilities', 5 UNION ALL
  SELECT 'LECTURER', 'ADMINISTRATIVE', 15, 2, 'institute committees/events', 3 UNION ALL
  SELECT 'LECTURER', 'ADMINISTRATIVE', 15, 3, 'curriculum/BoS/academic support', 3 UNION ALL
  SELECT 'LECTURER', 'ADMINISTRATIVE', 15, 4, 'quality/accreditation contributions', 2 UNION ALL
  SELECT 'LECTURER', 'ADMINISTRATIVE', 15, 5, 'measurable institutional/student-service contribution', 2 UNION ALL
  SELECT 'LECTURER', 'RESEARCH_PUBLICATIONS', 5, 1, 'Quality publications', 3 UNION ALL
  SELECT 'LECTURER', 'RESEARCH_PUBLICATIONS', 5, 2, 'citations/research profile', 1 UNION ALL
  SELECT 'LECTURER', 'RESEARCH_PUBLICATIONS', 5, 3, 'research activity/collaboration', 1 UNION ALL

  SELECT 'ASST_PROF', 'TEACHING_LEARNING', 30, 1, 'Workload & course delivery', 8 UNION ALL
  SELECT 'ASST_PROF', 'TEACHING_LEARNING', 30, 2, 'pass/result performance', 8 UNION ALL
  SELECT 'ASST_PROF', 'TEACHING_LEARNING', 30, 3, 'feedback', 6 UNION ALL
  SELECT 'ASST_PROF', 'TEACHING_LEARNING', 30, 4, 'course-file/assessment quality', 4 UNION ALL
  SELECT 'ASST_PROF', 'TEACHING_LEARNING', 30, 5, 'innovation/remedial/advanced learning', 4 UNION ALL
  SELECT 'ASST_PROF', 'STUDENT_MENTORING', 12, 1, 'Mentoring', 4 UNION ALL
  SELECT 'ASST_PROF', 'STUDENT_MENTORING', 12, 2, 'project guidance', 3 UNION ALL
  SELECT 'ASST_PROF', 'STUDENT_MENTORING', 12, 3, 'student achievements', 3 UNION ALL
  SELECT 'ASST_PROF', 'STUDENT_MENTORING', 12, 4, 'academic/placement/competitive-exam support', 2 UNION ALL
  SELECT 'ASST_PROF', 'FDP_CERTIFICATIONS', 10, 1, 'FDP/workshops/training', 6 UNION ALL
  SELECT 'ASST_PROF', 'FDP_CERTIFICATIONS', 10, 2, 'relevant certification', 4 UNION ALL
  SELECT 'ASST_PROF', 'ADMINISTRATIVE', 15, 1, 'Department responsibilities', 4 UNION ALL
  SELECT 'ASST_PROF', 'ADMINISTRATIVE', 15, 2, 'institute roles', 3 UNION ALL
  SELECT 'ASST_PROF', 'ADMINISTRATIVE', 15, 3, 'curriculum/BoS', 3 UNION ALL
  SELECT 'ASST_PROF', 'ADMINISTRATIVE', 15, 4, 'quality/accreditation', 3 UNION ALL
  SELECT 'ASST_PROF', 'ADMINISTRATIVE', 15, 5, 'measurable institutional contribution', 2 UNION ALL
  SELECT 'ASST_PROF', 'RESEARCH_PUBLICATIONS', 15, 1, 'SCI/SCIE/quality Scopus publications', 8 UNION ALL
  SELECT 'ASST_PROF', 'RESEARCH_PUBLICATIONS', 15, 2, 'other eligible publications', 2 UNION ALL
  SELECT 'ASST_PROF', 'RESEARCH_PUBLICATIONS', 15, 3, 'citations/profile', 2 UNION ALL
  SELECT 'ASST_PROF', 'RESEARCH_PUBLICATIONS', 15, 4, 'conference/research dissemination', 1 UNION ALL
  SELECT 'ASST_PROF', 'RESEARCH_PUBLICATIONS', 15, 5, 'research collaboration/academic output', 2 UNION ALL

  SELECT 'SR_ASST_PROF', 'TEACHING_LEARNING', 25, 1, 'Workload & course delivery', 6 UNION ALL
  SELECT 'SR_ASST_PROF', 'TEACHING_LEARNING', 25, 2, 'result performance', 6 UNION ALL
  SELECT 'SR_ASST_PROF', 'TEACHING_LEARNING', 25, 3, 'feedback', 5 UNION ALL
  SELECT 'SR_ASST_PROF', 'TEACHING_LEARNING', 25, 4, 'course/assessment quality', 4 UNION ALL
  SELECT 'SR_ASST_PROF', 'TEACHING_LEARNING', 25, 5, 'innovation/academic contribution', 4 UNION ALL
  SELECT 'SR_ASST_PROF', 'STUDENT_MENTORING', 10, 1, 'Mentoring quality', 3 UNION ALL
  SELECT 'SR_ASST_PROF', 'STUDENT_MENTORING', 10, 2, 'project guidance', 3 UNION ALL
  SELECT 'SR_ASST_PROF', 'STUDENT_MENTORING', 10, 3, 'student achievements', 2 UNION ALL
  SELECT 'SR_ASST_PROF', 'STUDENT_MENTORING', 10, 4, 'academic/placement support', 2 UNION ALL
  SELECT 'SR_ASST_PROF', 'FDP_CERTIFICATIONS', 8, 1, 'FDP/workshops/training', 5 UNION ALL
  SELECT 'SR_ASST_PROF', 'FDP_CERTIFICATIONS', 8, 2, 'certification', 3 UNION ALL
  SELECT 'SR_ASST_PROF', 'ADMINISTRATIVE', 15, 1, 'Department/institute responsibility', 4 UNION ALL
  SELECT 'SR_ASST_PROF', 'ADMINISTRATIVE', 15, 2, 'curriculum/BoS', 3 UNION ALL
  SELECT 'SR_ASST_PROF', 'ADMINISTRATIVE', 15, 3, 'quality/accreditation', 4 UNION ALL
  SELECT 'SR_ASST_PROF', 'ADMINISTRATIVE', 15, 4, 'committee/event leadership', 2 UNION ALL
  SELECT 'SR_ASST_PROF', 'ADMINISTRATIVE', 15, 5, 'institutional improvement', 2 UNION ALL
  SELECT 'SR_ASST_PROF', 'RESEARCH_PUBLICATIONS', 20, 1, 'SCI/SCIE/quality Scopus publications', 10 UNION ALL
  SELECT 'SR_ASST_PROF', 'RESEARCH_PUBLICATIONS', 20, 2, 'citations/profile', 3 UNION ALL
  SELECT 'SR_ASST_PROF', 'RESEARCH_PUBLICATIONS', 20, 3, 'conference/research dissemination', 2 UNION ALL
  SELECT 'SR_ASST_PROF', 'RESEARCH_PUBLICATIONS', 20, 4, 'research collaboration', 2 UNION ALL
  SELECT 'SR_ASST_PROF', 'RESEARCH_PUBLICATIONS', 20, 5, 'Ph.D./PG research guidance', 3 UNION ALL

  SELECT 'ASSOC_PROF', 'TEACHING_LEARNING', 20, 1, 'Course delivery/workload', 5 UNION ALL
  SELECT 'ASSOC_PROF', 'TEACHING_LEARNING', 20, 2, 'result/academic quality', 5 UNION ALL
  SELECT 'ASSOC_PROF', 'TEACHING_LEARNING', 20, 3, 'feedback', 4 UNION ALL
  SELECT 'ASSOC_PROF', 'TEACHING_LEARNING', 20, 4, 'course/assessment development', 3 UNION ALL
  SELECT 'ASSOC_PROF', 'TEACHING_LEARNING', 20, 5, 'innovative teaching/academic leadership', 3 UNION ALL
  SELECT 'ASSOC_PROF', 'STUDENT_MENTORING', 8, 1, 'Mentoring', 2 UNION ALL
  SELECT 'ASSOC_PROF', 'STUDENT_MENTORING', 8, 2, 'project/PG guidance', 2 UNION ALL
  SELECT 'ASSOC_PROF', 'STUDENT_MENTORING', 8, 3, 'student achievements', 2 UNION ALL
  SELECT 'ASSOC_PROF', 'STUDENT_MENTORING', 8, 4, 'advanced learner/placement/research support', 2 UNION ALL
  SELECT 'ASSOC_PROF', 'FDP_CERTIFICATIONS', 5, 1, 'FDP/workshops/training', 3 UNION ALL
  SELECT 'ASSOC_PROF', 'FDP_CERTIFICATIONS', 5, 2, 'certification', 2 UNION ALL
  SELECT 'ASSOC_PROF', 'ADMINISTRATIVE', 15, 1, 'Academic/administrative leadership', 4 UNION ALL
  SELECT 'ASSOC_PROF', 'ADMINISTRATIVE', 15, 2, 'curriculum/BoS', 3 UNION ALL
  SELECT 'ASSOC_PROF', 'ADMINISTRATIVE', 15, 3, 'quality/accreditation', 4 UNION ALL
  SELECT 'ASSOC_PROF', 'ADMINISTRATIVE', 15, 4, 'committee/institutional leadership', 2 UNION ALL
  SELECT 'ASSOC_PROF', 'ADMINISTRATIVE', 15, 5, 'measurable improvement', 2 UNION ALL
  SELECT 'ASSOC_PROF', 'RESEARCH_PUBLICATIONS', 25, 1, 'Quality journal publications', 12 UNION ALL
  SELECT 'ASSOC_PROF', 'RESEARCH_PUBLICATIONS', 25, 2, 'citations/research impact', 4 UNION ALL
  SELECT 'ASSOC_PROF', 'RESEARCH_PUBLICATIONS', 25, 3, 'research guidance', 4 UNION ALL
  SELECT 'ASSOC_PROF', 'RESEARCH_PUBLICATIONS', 25, 4, 'conference/research dissemination', 2 UNION ALL
  SELECT 'ASSOC_PROF', 'RESEARCH_PUBLICATIONS', 25, 5, 'collaboration/research leadership', 3 UNION ALL

  SELECT 'PROFESSOR', 'TEACHING_LEARNING', 15, 1, 'Teaching quality/workload', 4 UNION ALL
  SELECT 'PROFESSOR', 'TEACHING_LEARNING', 15, 2, 'result/academic quality', 4 UNION ALL
  SELECT 'PROFESSOR', 'TEACHING_LEARNING', 15, 3, 'feedback', 3 UNION ALL
  SELECT 'PROFESSOR', 'TEACHING_LEARNING', 15, 4, 'curriculum/assessment contribution', 2 UNION ALL
  SELECT 'PROFESSOR', 'TEACHING_LEARNING', 15, 5, 'innovative teaching/academic leadership', 2 UNION ALL
  SELECT 'PROFESSOR', 'STUDENT_MENTORING', 5, 1, 'Academic/research mentoring', 2 UNION ALL
  SELECT 'PROFESSOR', 'STUDENT_MENTORING', 5, 2, 'student/project/PG guidance', 1 UNION ALL
  SELECT 'PROFESSOR', 'STUDENT_MENTORING', 5, 3, 'significant student achievements', 2 UNION ALL
  SELECT 'PROFESSOR', 'FDP_CERTIFICATIONS', 5, 1, 'Relevant advanced FDP/training', 3 UNION ALL
  SELECT 'PROFESSOR', 'FDP_CERTIFICATIONS', 5, 2, 'certification/academic updating', 2 UNION ALL
  SELECT 'PROFESSOR', 'ADMINISTRATIVE', 20, 1, 'Academic/institutional leadership', 5 UNION ALL
  SELECT 'PROFESSOR', 'ADMINISTRATIVE', 20, 2, 'curriculum/BoS', 4 UNION ALL
  SELECT 'PROFESSOR', 'ADMINISTRATIVE', 20, 3, 'quality/accreditation', 5 UNION ALL
  SELECT 'PROFESSOR', 'ADMINISTRATIVE', 20, 4, 'major institutional responsibility', 3 UNION ALL
  SELECT 'PROFESSOR', 'ADMINISTRATIVE', 20, 5, 'measurable institutional development', 3 UNION ALL
  SELECT 'PROFESSOR', 'RESEARCH_PUBLICATIONS', 25, 1, 'Quality journal publications', 10 UNION ALL
  SELECT 'PROFESSOR', 'RESEARCH_PUBLICATIONS', 25, 2, 'citations/research impact', 5 UNION ALL
  SELECT 'PROFESSOR', 'RESEARCH_PUBLICATIONS', 25, 3, 'Ph.D./PG guidance', 5 UNION ALL
  SELECT 'PROFESSOR', 'RESEARCH_PUBLICATIONS', 25, 4, 'research leadership/collaboration', 3 UNION ALL
  SELECT 'PROFESSOR', 'RESEARCH_PUBLICATIONS', 25, 5, 'conference/editorial/reviewer contributions', 2
) m ON m.cadre = c.code AND m.criterion = k.criterion AND m.criterion_max = k.max_marks;
