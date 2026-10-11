-- Dropdown and reference data moves from code lists and CHECK constraints into the database (2026-10-11).
--
-- reference_lists names each list; reference_values holds its values. An administrator can add values, rename them and
-- switch them off under Admin > Lists; a value is never deleted, so a record that holds it keeps its label. A value marked
-- "locked" is one the application's own rules depend on (the journal indexings and project statuses that earn marks, the
-- Ph.D. status that opens a section, ...): its label can change but it cannot be switched off. A value that has a parent
-- belongs to a value of the parent list (a branch to its program). A list of kind NUMBER holds whole numbers; one of kind
-- FREE only suggests words while someone types (the person may still enter their own).
--
-- The application now checks every chosen value against these tables, so the CHECK constraints that fixed the allowed
-- values in each section table are dropped (they would refuse a value an administrator adds). Columns, data and the
-- values already stored are untouched.
CREATE TABLE reference_lists (
  code        VARCHAR(40)  NOT NULL PRIMARY KEY,
  name        VARCHAR(120) NOT NULL,
  description VARCHAR(300) NULL,
  parent_list VARCHAR(40)  NULL,
  kind        VARCHAR(8)   NOT NULL DEFAULT 'CODE',
  CONSTRAINT reference_lists_kind_chk CHECK (kind IN ('CODE', 'NUMBER', 'FREE')),
  CONSTRAINT reference_lists_parent_fk FOREIGN KEY (parent_list) REFERENCES reference_lists (code)
);

CREATE TABLE reference_values (
  id           BIGINT       NOT NULL AUTO_INCREMENT PRIMARY KEY,
  list_code    VARCHAR(40)  NOT NULL,
  code         VARCHAR(120) NOT NULL,
  parent_code  VARCHAR(120) NOT NULL DEFAULT '',
  label        VARCHAR(160) NOT NULL,
  report_label VARCHAR(160) NULL,
  sort_order   INT          NOT NULL DEFAULT 0,
  active       BOOLEAN      NOT NULL DEFAULT TRUE,
  locked       BOOLEAN      NOT NULL DEFAULT FALSE,
  created_by   BIGINT       NULL,
  created_at   TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at   TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  CONSTRAINT reference_values_list_fk FOREIGN KEY (list_code) REFERENCES reference_lists (code),
  CONSTRAINT reference_values_unique UNIQUE (list_code, parent_code, code),
  CONSTRAINT reference_values_locked_active_chk CHECK (NOT locked OR active)
);
CREATE INDEX reference_values_list_idx ON reference_values (list_code, sort_order);

INSERT INTO reference_lists (code, name, description, parent_list, kind) VALUES
  ('PHD_STATUS', 'Ph.D. status', 'General information and faculty profiles', NULL, 'CODE'),
  ('COURSE_ROLE', 'Role in a course', 'Teaching & Learning: courses handled', NULL, 'CODE'),
  ('COURSE_TYPE', 'Course type', 'Teaching & Learning: courses handled', NULL, 'CODE'),
  ('PROGRAM', 'Program', 'Teaching & Learning: courses handled', NULL, 'CODE'),
  ('SEMESTER', 'Semester', 'Teaching & Learning: courses handled (whole numbers)', NULL, 'NUMBER'),
  ('SECTION', 'Section', 'Teaching & Learning: courses handled', NULL, 'CODE'),
  ('HOURS_PER_WEEK', 'Hours per week', 'Teaching & Learning: courses handled (whole numbers)', NULL, 'NUMBER'),
  ('ACHIEVEMENT_LEVEL', 'Level', 'Student achievements and memberships, awards & recognitions', NULL, 'CODE'),
  ('PROJECT_LEVEL', 'Student project level', 'Mentoring: student projects guided', NULL, 'CODE'),
  ('PROJECT_OUTCOME', 'Student project outcome', 'Mentoring: student projects guided', NULL, 'CODE'),
  ('FDP_MODE', 'Mode of a program', 'FDPs: workshops, seminars and training programs attended', NULL, 'CODE'),
  ('CERT_PLATFORM', 'Certification platform', 'FDPs & certifications: "Other" asks for the platform name', NULL, 'CODE'),
  ('AUTHOR_POSITION', 'Author position', 'Research: journal publications (whole numbers)', NULL, 'NUMBER'),
  ('JOURNAL_INDEXING', 'Journal indexing', 'Research: journal publications; SCI/SCIE and Scopus earn marks', NULL, 'CODE'),
  ('CONFERENCE_LEVEL', 'Conference level', 'Research: conference papers published', NULL, 'CODE'),
  ('METRICS_PLATFORM', 'Research profile platform', 'Research: one row of profile metrics for each platform', NULL, 'CODE'),
  ('SCHOLAR_DEGREE', 'Scholar degree', 'Research: scholars guided; the three degrees earn marks', NULL, 'CODE'),
  ('SCHOLAR_STATUS', 'Scholar status', 'Research: scholars guided; the three statuses earn marks', NULL, 'CODE'),
  ('PHD_STAGE', 'Own Ph.D. stage', 'Research: own Ph.D. progress', NULL, 'CODE'),
  ('FUNDED_ROLE', 'Role in a funded project', 'Funded projects / consultancy', NULL, 'CODE'),
  ('FUNDED_TYPE', 'Type of funded project', 'Funded projects / consultancy', NULL, 'CODE'),
  ('FUNDED_STATUS', 'Status of a funded project', 'Funded projects / consultancy; both statuses earn marks', NULL, 'CODE'),
  ('PATENT_TYPE', 'Patent type', 'Patents, books & IPR', NULL, 'CODE'),
  ('PATENT_STATUS', 'Patent status', 'Patents, books & IPR; Published and Granted earn marks', NULL, 'CODE'),
  ('BOOK_TYPE', 'Book or chapter', 'Patents, books & IPR; both earn marks', NULL, 'CODE'),
  ('OUTREACH_ROLE', 'Outreach role', 'Outreach', NULL, 'CODE'),
  ('INSTITUTE_ROLE_SUGGESTION', 'Institute-level role suggestions', 'Administration: offered under Role for institute-level roles', NULL, 'FREE'),
  ('DEPARTMENT_ROLE_SUGGESTION', 'Department-level role suggestions', 'Administration: offered under Role for department-level roles', NULL, 'FREE'),
  ('AWARDING_BODY_SUGGESTION', 'Awarding body suggestions', 'Memberships, awards & recognitions: offered under Professional / awarding body', NULL, 'FREE'),
  ('BRANCH', 'Branch', 'Teaching & Learning: each branch belongs to a program', 'PROGRAM', 'CODE');

INSERT INTO reference_values (list_code, code, parent_code, label, report_label, sort_order, locked) VALUES
  ('PHD_STATUS', 'AWARDED', '', 'Awarded', NULL, 10, FALSE),
  ('PHD_STATUS', 'PURSUING', '', 'Pursuing', NULL, 20, TRUE),
  ('PHD_STATUS', 'NOT_APPLICABLE', '', 'Not applicable', NULL, 30, FALSE),
  ('COURSE_ROLE', 'COORDINATOR', '', 'Course Coordinator', NULL, 10, FALSE),
  ('COURSE_ROLE', 'INSTRUCTOR', '', 'Course Instructor', NULL, 20, FALSE),
  ('COURSE_TYPE', 'THEORY', '', 'Theory', NULL, 10, FALSE),
  ('COURSE_TYPE', 'LAB', '', 'Lab', NULL, 20, FALSE),
  ('PROGRAM', 'B_TECH', '', 'B.Tech', NULL, 10, FALSE),
  ('PROGRAM', 'M_TECH', '', 'M.Tech', NULL, 20, FALSE),
  ('PROGRAM', 'DIPLOMA', '', 'Diploma', NULL, 30, FALSE),
  ('PROGRAM', 'MBA', '', 'MBA', NULL, 40, FALSE),
  ('PROGRAM', 'PHARMACY', '', 'Pharmacy', NULL, 50, FALSE),
  ('BRANCH', 'CSE', 'B_TECH', 'CSE', NULL, 10, FALSE),
  ('BRANCH', 'AIML', 'B_TECH', 'AI & ML', NULL, 20, FALSE),
  ('BRANCH', 'CAI', 'B_TECH', 'CAI', NULL, 30, FALSE),
  ('BRANCH', 'AIM', 'B_TECH', 'AIM', NULL, 40, FALSE),
  ('BRANCH', 'CSDS', 'B_TECH', 'CSDS', NULL, 50, FALSE),
  ('BRANCH', 'ECE', 'B_TECH', 'ECE', NULL, 60, FALSE),
  ('BRANCH', 'EEE', 'B_TECH', 'EEE', NULL, 70, FALSE),
  ('BRANCH', 'ME', 'B_TECH', 'ME', NULL, 80, FALSE),
  ('BRANCH', 'CE', 'B_TECH', 'CE', NULL, 90, FALSE),
  ('BRANCH', 'BSH', 'B_TECH', 'BSH', NULL, 100, FALSE),
  ('BRANCH', 'CSE', 'M_TECH', 'CSE', NULL, 10, FALSE),
  ('BRANCH', 'ECE', 'M_TECH', 'ECE', NULL, 20, FALSE),
  ('BRANCH', 'EEE', 'M_TECH', 'EEE', NULL, 30, FALSE),
  ('BRANCH', 'ME', 'M_TECH', 'ME', NULL, 40, FALSE),
  ('BRANCH', 'CE', 'M_TECH', 'CE', NULL, 50, FALSE),
  ('BRANCH', 'CSE', 'DIPLOMA', 'CSE', NULL, 10, FALSE),
  ('BRANCH', 'ECE', 'DIPLOMA', 'ECE', NULL, 20, FALSE),
  ('BRANCH', 'EEE', 'DIPLOMA', 'EEE', NULL, 30, FALSE),
  ('BRANCH', 'ME', 'DIPLOMA', 'ME', NULL, 40, FALSE),
  ('BRANCH', 'CE', 'DIPLOMA', 'CE', NULL, 50, FALSE),
  ('BRANCH', 'MBA', 'MBA', 'MBA', NULL, 10, FALSE),
  ('BRANCH', 'PHARMACEUTICS', 'PHARMACY', 'Pharmaceutics', NULL, 10, FALSE),
  ('BRANCH', 'PHARMACEUTICAL_CHEMISTRY', 'PHARMACY', 'Pharmaceutical Chemistry', NULL, 20, FALSE),
  ('BRANCH', 'PHARMACOLOGY', 'PHARMACY', 'Pharmacology', NULL, 30, FALSE),
  ('BRANCH', 'PHARMACOGNOSY', 'PHARMACY', 'Pharmacognosy', NULL, 40, FALSE),
  ('BRANCH', 'PHARMACY_PRACTICE', 'PHARMACY', 'Pharmacy Practice', NULL, 50, FALSE),
  ('SEMESTER', '1', '', '1', NULL, 10, FALSE),
  ('SEMESTER', '2', '', '2', NULL, 20, FALSE),
  ('SEMESTER', '3', '', '3', NULL, 30, FALSE),
  ('SEMESTER', '4', '', '4', NULL, 40, FALSE),
  ('SEMESTER', '5', '', '5', NULL, 50, FALSE),
  ('SEMESTER', '6', '', '6', NULL, 60, FALSE),
  ('SEMESTER', '7', '', '7', NULL, 70, FALSE),
  ('SEMESTER', '8', '', '8', NULL, 80, FALSE),
  ('SEMESTER', '9', '', '9', NULL, 90, FALSE),
  ('SEMESTER', '10', '', '10', NULL, 100, FALSE),
  ('SEMESTER', '11', '', '11', NULL, 110, FALSE),
  ('SEMESTER', '12', '', '12', NULL, 120, FALSE),
  ('SECTION', 'A', '', 'A', NULL, 10, FALSE),
  ('SECTION', 'B', '', 'B', NULL, 20, FALSE),
  ('SECTION', 'C', '', 'C', NULL, 30, FALSE),
  ('SECTION', 'D', '', 'D', NULL, 40, FALSE),
  ('SECTION', 'E', '', 'E', NULL, 50, FALSE),
  ('HOURS_PER_WEEK', '1', '', '1', NULL, 10, FALSE),
  ('HOURS_PER_WEEK', '2', '', '2', NULL, 20, FALSE),
  ('HOURS_PER_WEEK', '3', '', '3', NULL, 30, FALSE),
  ('HOURS_PER_WEEK', '4', '', '4', NULL, 40, FALSE),
  ('HOURS_PER_WEEK', '5', '', '5', NULL, 50, FALSE),
  ('HOURS_PER_WEEK', '6', '', '6', NULL, 60, FALSE),
  ('ACHIEVEMENT_LEVEL', 'INST', '', 'Institute', 'Inst', 10, FALSE),
  ('ACHIEVEMENT_LEVEL', 'STATE', '', 'State', NULL, 20, FALSE),
  ('ACHIEVEMENT_LEVEL', 'NAT', '', 'National', 'Nat', 30, FALSE),
  ('ACHIEVEMENT_LEVEL', 'INTL', '', 'International', 'Intl', 40, FALSE),
  ('PROJECT_LEVEL', 'DIPLOMA', '', 'Diploma', NULL, 10, FALSE),
  ('PROJECT_LEVEL', 'UG', '', 'UG', NULL, 20, TRUE),
  ('PROJECT_LEVEL', 'PG', '', 'PG', NULL, 30, FALSE),
  ('PROJECT_OUTCOME', 'PAPER', '', 'Paper', NULL, 10, FALSE),
  ('PROJECT_OUTCOME', 'PATENT', '', 'Patent', NULL, 20, FALSE),
  ('PROJECT_OUTCOME', 'PROTOTYPE', '', 'Prototype', NULL, 30, FALSE),
  ('PROJECT_OUTCOME', 'COMPETITION', '', 'Competition', NULL, 40, FALSE),
  ('PROJECT_OUTCOME', 'NONE', '', 'None', NULL, 50, FALSE),
  ('FDP_MODE', 'OFFLINE', '', 'Offline', NULL, 10, FALSE),
  ('FDP_MODE', 'ONLINE', '', 'Online', NULL, 20, FALSE),
  ('FDP_MODE', 'BLENDED', '', 'Blended', NULL, 30, FALSE),
  ('CERT_PLATFORM', 'NPTEL', '', 'NPTEL', NULL, 10, FALSE),
  ('CERT_PLATFORM', 'SWAYAM', '', 'Swayam', NULL, 20, FALSE),
  ('CERT_PLATFORM', 'COURSERA', '', 'Coursera', NULL, 30, FALSE),
  ('CERT_PLATFORM', 'OTHER', '', 'Other', NULL, 40, TRUE),
  ('AUTHOR_POSITION', '1', '', '1st author', '1', 10, FALSE),
  ('AUTHOR_POSITION', '2', '', '2nd author', '2', 20, FALSE),
  ('AUTHOR_POSITION', '3', '', '3rd author', '3', 30, FALSE),
  ('AUTHOR_POSITION', '4', '', '4th author', '4', 40, FALSE),
  ('AUTHOR_POSITION', '5', '', '5th author', '5', 50, FALSE),
  ('AUTHOR_POSITION', '6', '', '6th author', '6', 60, FALSE),
  ('AUTHOR_POSITION', '7', '', '7th author', '7', 70, FALSE),
  ('AUTHOR_POSITION', '8', '', '8th author', '8', 80, FALSE),
  ('JOURNAL_INDEXING', 'SCI_SCIE', '', 'SCI / SCIE', NULL, 10, TRUE),
  ('JOURNAL_INDEXING', 'SCOPUS', '', 'Scopus', NULL, 20, TRUE),
  ('JOURNAL_INDEXING', 'UGC_CARE_ABDC', '', 'UGC-CARE / ABDC', NULL, 30, FALSE),
  ('JOURNAL_INDEXING', 'OTHERS', '', 'Others', NULL, 40, FALSE),
  ('CONFERENCE_LEVEL', 'NAT', '', 'National', 'Nat', 10, FALSE),
  ('CONFERENCE_LEVEL', 'INTL', '', 'International', 'Intl', 20, FALSE),
  ('METRICS_PLATFORM', 'GOOGLE_SCHOLAR', '', 'Google Scholar', NULL, 10, FALSE),
  ('METRICS_PLATFORM', 'SCOPUS', '', 'Scopus', NULL, 20, FALSE),
  ('METRICS_PLATFORM', 'WEB_OF_SCIENCE', '', 'Web of Science', NULL, 30, FALSE),
  ('SCHOLAR_DEGREE', 'PHD', '', 'Ph.D.', NULL, 10, TRUE),
  ('SCHOLAR_DEGREE', 'MTECH', '', 'M.Tech', NULL, 20, TRUE),
  ('SCHOLAR_DEGREE', 'MBA', '', 'MBA', NULL, 30, TRUE),
  ('SCHOLAR_STATUS', 'REGISTERED', '', 'Registered', NULL, 10, TRUE),
  ('SCHOLAR_STATUS', 'SUBMITTED', '', 'Submitted', NULL, 20, TRUE),
  ('SCHOLAR_STATUS', 'AWARDED', '', 'Awarded', NULL, 30, TRUE),
  ('PHD_STAGE', 'COURSE_WORK', '', 'Course work', NULL, 10, FALSE),
  ('PHD_STAGE', 'COMPREHENSIVE_PROPOSAL', '', 'Comprehensive / Proposal', NULL, 20, FALSE),
  ('PHD_STAGE', 'SYNOPSIS', '', 'Synopsis / Pre-Ph.D.', NULL, 30, FALSE),
  ('PHD_STAGE', 'THESIS_SUBMITTED', '', 'Thesis submitted', NULL, 40, FALSE),
  ('PHD_STAGE', 'VIVA_COMPLETED', '', 'Viva-voce completed', NULL, 50, FALSE),
  ('FUNDED_ROLE', 'PI', '', 'PI', NULL, 10, FALSE),
  ('FUNDED_ROLE', 'CO_PI', '', 'Co-PI', NULL, 20, FALSE),
  ('FUNDED_TYPE', 'RESEARCH', '', 'Research', NULL, 10, FALSE),
  ('FUNDED_TYPE', 'CONSULTANCY', '', 'Consultancy', NULL, 20, FALSE),
  ('FUNDED_STATUS', 'SANCTIONED', '', 'Sanctioned', NULL, 10, TRUE),
  ('FUNDED_STATUS', 'APPLIED', '', 'Applied', NULL, 20, TRUE),
  ('PATENT_TYPE', 'DESIGN', '', 'Design', NULL, 10, FALSE),
  ('PATENT_TYPE', 'UTILITY', '', 'Utility', NULL, 20, FALSE),
  ('PATENT_TYPE', 'COPYRIGHT', '', 'Copyright', NULL, 30, FALSE),
  ('PATENT_STATUS', 'FILED', '', 'Filed', NULL, 10, FALSE),
  ('PATENT_STATUS', 'PUBLISHED', '', 'Published', NULL, 20, TRUE),
  ('PATENT_STATUS', 'GRANTED', '', 'Granted', NULL, 30, TRUE),
  ('BOOK_TYPE', 'BOOK', '', 'Book', NULL, 10, TRUE),
  ('BOOK_TYPE', 'CHAPTER', '', 'Chapter', NULL, 20, TRUE),
  ('OUTREACH_ROLE', 'CONFERENCE_SESSION_CHAIR', '', 'Conference Session Chair', NULL, 10, FALSE),
  ('OUTREACH_ROLE', 'EXPERT_LECTURE_DELIVERED', '', 'Expert Lectures Delivered', NULL, 20, FALSE),
  ('OUTREACH_ROLE', 'RESOURCE_PERSON', '', 'Resource Person', NULL, 30, FALSE),
  ('OUTREACH_ROLE', 'EDITORIAL_BOARD_MEMBER', '', 'Editorial Board Member', NULL, 40, FALSE),
  ('OUTREACH_ROLE', 'JOURNAL_REVIEWER', '', 'Reviewer for Journals', NULL, 50, FALSE),
  ('OUTREACH_ROLE', 'EXTERNAL_EXAMINER', '', 'External Examiner', NULL, 60, FALSE),
  ('OUTREACH_ROLE', 'EXTERNAL_THESIS_EVALUATED', '', 'External Thesis Evaluated', NULL, 70, FALSE),
  ('OUTREACH_ROLE', 'VISITING_RESEARCHER', '', 'Visiting Researcher', NULL, 80, FALSE),
  ('OUTREACH_ROLE', 'INDUSTRY_INTERACTION_MOU', '', 'Industry Interaction or MoU', NULL, 90, FALSE),
  ('OUTREACH_ROLE', 'INTERNATIONAL_CONFERENCE_ATTENDED', '', 'International Conferences Attended', NULL, 100, FALSE),
  ('OUTREACH_ROLE', 'OTHERS', '', 'Others', NULL, 110, FALSE),
  ('INSTITUTE_ROLE_SUGGESTION', 'NAAC / NBA criterion coordinator', '', 'NAAC / NBA criterion coordinator', NULL, 10, FALSE),
  ('INSTITUTE_ROLE_SUGGESTION', 'IQAC', '', 'IQAC', NULL, 20, FALSE),
  ('INSTITUTE_ROLE_SUGGESTION', 'Exam Cell', '', 'Exam Cell', NULL, 30, FALSE),
  ('INSTITUTE_ROLE_SUGGESTION', 'Training & Placement', '', 'Training & Placement', NULL, 40, FALSE),
  ('INSTITUTE_ROLE_SUGGESTION', 'NSS / NCC', '', 'NSS / NCC', NULL, 50, FALSE),
  ('INSTITUTE_ROLE_SUGGESTION', 'Anti-Ragging', '', 'Anti-Ragging', NULL, 60, FALSE),
  ('DEPARTMENT_ROLE_SUGGESTION', 'Class Coordinator', '', 'Class Coordinator', NULL, 10, FALSE),
  ('DEPARTMENT_ROLE_SUGGESTION', 'Module Coordinator', '', 'Module Coordinator', NULL, 20, FALSE),
  ('DEPARTMENT_ROLE_SUGGESTION', 'Lab In-charge', '', 'Lab In-charge', NULL, 30, FALSE),
  ('DEPARTMENT_ROLE_SUGGESTION', 'Time-table In-charge', '', 'Time-table In-charge', NULL, 40, FALSE),
  ('DEPARTMENT_ROLE_SUGGESTION', 'NAAC / NBA criterion coordinator', '', 'NAAC / NBA criterion coordinator', NULL, 50, FALSE),
  ('DEPARTMENT_ROLE_SUGGESTION', 'IQAC', '', 'IQAC', NULL, 60, FALSE),
  ('DEPARTMENT_ROLE_SUGGESTION', 'Exam Cell', '', 'Exam Cell', NULL, 70, FALSE),
  ('DEPARTMENT_ROLE_SUGGESTION', 'Training & Placement', '', 'Training & Placement', NULL, 80, FALSE),
  ('DEPARTMENT_ROLE_SUGGESTION', 'NSS / NCC', '', 'NSS / NCC', NULL, 90, FALSE),
  ('DEPARTMENT_ROLE_SUGGESTION', 'Anti-Ragging', '', 'Anti-Ragging', NULL, 100, FALSE),
  ('AWARDING_BODY_SUGGESTION', 'IEEE', '', 'IEEE', NULL, 10, FALSE),
  ('AWARDING_BODY_SUGGESTION', 'CSI', '', 'CSI', NULL, 20, FALSE),
  ('AWARDING_BODY_SUGGESTION', 'ISTE', '', 'ISTE', NULL, 30, FALSE),
  ('AWARDING_BODY_SUGGESTION', 'IE(I)', '', 'IE(I)', NULL, 40, FALSE);

-- the CHECK constraints that fixed the allowed values (the values are now checked against reference_values)
ALTER TABLE books DROP CHECK books_chk_1;
ALTER TABLE certifications DROP CHECK certifications_chk_1;
ALTER TABLE conference_papers DROP CHECK conference_papers_chk_1;
ALTER TABLE faculty_profiles DROP CHECK faculty_profiles_chk_1;
ALTER TABLE fdps DROP CHECK fdps_chk_1;
ALTER TABLE funded_projects DROP CHECK funded_projects_chk_1;
ALTER TABLE funded_projects DROP CHECK funded_projects_chk_2;
ALTER TABLE funded_projects DROP CHECK funded_projects_chk_3;
ALTER TABLE general_information DROP CHECK general_information_chk_1;
ALTER TABLE journal_publications DROP CHECK journal_publications_author_position_chk;
ALTER TABLE journal_publications DROP CHECK journal_publications_chk_1;
ALTER TABLE memberships_awards DROP CHECK memberships_awards_chk_1;
ALTER TABLE outreach DROP CHECK outreach_chk_1;
ALTER TABLE patents_ipr DROP CHECK patents_ipr_chk_1;
ALTER TABLE patents_ipr DROP CHECK patents_ipr_chk_2;
ALTER TABLE phd_progress DROP CHECK phd_progress_chk_1;
ALTER TABLE research_metrics DROP CHECK research_metrics_chk_1;
ALTER TABLE research_scholars DROP CHECK research_scholars_chk_1;
ALTER TABLE research_scholars DROP CHECK research_scholars_chk_2;
ALTER TABLE student_achievements DROP CHECK student_achievements_chk_1;
ALTER TABLE student_projects DROP CHECK student_projects_chk_1;
ALTER TABLE student_projects DROP CHECK student_projects_outcome_chk;
ALTER TABLE teaching_courses DROP CHECK teaching_courses_branch_chk;
ALTER TABLE teaching_courses DROP CHECK teaching_courses_chk_1;
ALTER TABLE teaching_courses DROP CHECK teaching_courses_class_section_chk;
ALTER TABLE teaching_courses DROP CHECK teaching_courses_course_role_chk;
ALTER TABLE teaching_courses DROP CHECK teaching_courses_program_chk;
