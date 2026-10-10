-- Form changes of 2026-10-09.
--
-- 1. Institute and department roles carry a from date and a to date instead of free-text "period". Roles already entered
--    are given the dates of their appraisal's academic year, which is what a period inside that year amounted to; the
--    faculty member can correct them.
ALTER TABLE administrative_roles ADD COLUMN from_date DATE NULL, ADD COLUMN to_date DATE NULL;
UPDATE administrative_roles r
    JOIN appraisals a ON a.id = r.appraisal_id
    JOIN academic_years ay ON ay.id = a.academic_year_id
SET r.from_date = ay.start_date, r.to_date = ay.end_date;
ALTER TABLE administrative_roles MODIFY from_date DATE NOT NULL, MODIFY to_date DATE NOT NULL, DROP COLUMN period;

-- 2. The author position of a journal paper is a number from 1 to 8. A position written in words ("First author", "2nd",
--    "Corresponding author") becomes the number it names, and anything else 1.
UPDATE journal_publications SET author_position = CASE
    WHEN author_position REGEXP '^[1-8]$' THEN author_position
    WHEN LOWER(author_position) LIKE '%first%' OR author_position REGEXP '^1' THEN '1'
    WHEN LOWER(author_position) LIKE '%second%' OR author_position REGEXP '^2' THEN '2'
    WHEN LOWER(author_position) LIKE '%third%' OR author_position REGEXP '^3' THEN '3'
    WHEN LOWER(author_position) LIKE '%fourth%' OR author_position REGEXP '^4' THEN '4'
    WHEN LOWER(author_position) LIKE '%fifth%' OR author_position REGEXP '^5' THEN '5'
    WHEN LOWER(author_position) LIKE '%sixth%' OR author_position REGEXP '^6' THEN '6'
    WHEN LOWER(author_position) LIKE '%seventh%' OR author_position REGEXP '^7' THEN '7'
    WHEN LOWER(author_position) LIKE '%eighth%' OR author_position REGEXP '^8' THEN '8'
    ELSE '1' END;

-- 3. The program of a course is chosen from a list; the branch is written as the department's code.
UPDATE teaching_courses SET program = CASE
    WHEN LOWER(program) LIKE '%diploma%' THEN 'DIPLOMA'
    WHEN LOWER(program) LIKE '%mba%' THEN 'MBA'
    WHEN LOWER(program) LIKE '%pharm%' THEN 'PHARMACY'
    ELSE 'B_TECH' END,
    branch = UPPER(branch);

ALTER TABLE journal_publications ADD CONSTRAINT journal_publications_author_position_chk
    CHECK (author_position IN ('1','2','3','4','5','6','7','8'));

-- A branch that is not one of the college's is replaced by the faculty member's department, or by BSH when that is not a branch.
UPDATE teaching_courses c
    JOIN appraisals a ON a.id = c.appraisal_id
    JOIN faculty_profiles fp ON fp.id = a.faculty_id
    JOIN departments d ON d.id = fp.department_id
SET c.branch = CASE WHEN d.code IN ('CSE','AIML','ECE','EEE','ME','CE','BSH','MBA') THEN d.code ELSE 'BSH' END
WHERE c.branch NOT IN ('CSE','AIML','ECE','EEE','ME','CE','BSH','MBA','PHARMACEUTICS','PHARMACEUTICAL_CHEMISTRY','PHARMACOLOGY','PHARMACOGNOSY','PHARMACY_PRACTICE');
ALTER TABLE teaching_courses
    ADD CONSTRAINT teaching_courses_program_chk CHECK (program IN ('B_TECH','DIPLOMA','MBA','PHARMACY')),
    ADD CONSTRAINT teaching_courses_branch_chk CHECK (branch IN ('CSE','AIML','ECE','EEE','ME','CE','BSH','MBA','PHARMACEUTICS','PHARMACEUTICAL_CHEMISTRY','PHARMACOLOGY','PHARMACOGNOSY','PHARMACY_PRACTICE'));

-- 4. Marks are worked out from the entries alone: the faculty member no longer types a score over them, so the column
--    that held it goes. The reviewers' column stays (reserved).
ALTER TABLE appraisal_scores DROP CHECK appraisal_scores_self_nonneg;
ALTER TABLE appraisal_scores DROP COLUMN self_score;

-- 5. Sign-in attempts are no longer limited. The address an account last signed in from existed only to exempt the owner
--    from the per-account limit, so it goes. (sign_in_attempts stays: it still counts wrong current passwords when a
--    signed-in person changes their password.)
ALTER TABLE users DROP COLUMN last_login_address;
