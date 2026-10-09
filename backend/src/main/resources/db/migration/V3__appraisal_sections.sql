-- Part B section tables (repeatable records).
-- Deleting a draft appraisal cascades to its records; review_actions / appraisal_scores / audit_logs
-- are NOT cascaded, so reviewed work cannot be deleted by accident.
-- month_year columns hold 'YYYY-MM'. Enumerated values are CHECK-constrained (defence in depth).

-- Part A of the form (one row per appraisal). A snapshot taken from the faculty profile when the appraisal is
-- created and editable while it is a draft, so an old appraisal keeps what was true when it was submitted.
-- Name, employee ID, department, designation (cadre), e-mail and academic year come from the profile/appraisal.
CREATE TABLE general_information (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  appraisal_id BIGINT NOT NULL UNIQUE,
  contact_no VARCHAR(20),
  qualification_specialization VARCHAR(300),
  phd_status VARCHAR(16) NOT NULL DEFAULT 'NOT_APPLICABLE',
  joining_date_institution DATE,
  joining_date_designation DATE,
  teaching_experience_years DECIMAL(4,1),
  industry_experience_years DECIMAL(4,1),
  research_experience_years DECIMAL(4,1),
  research_ids VARCHAR(300),                 -- ORCID / Scopus / Google Scholar / Vidwan ID
  CHECK (phd_status IN ('AWARDED','PURSUING','NOT_APPLICABLE')),
  CHECK (teaching_experience_years IS NULL OR teaching_experience_years >= 0),
  CHECK (industry_experience_years IS NULL OR industry_experience_years >= 0),
  CHECK (research_experience_years IS NULL OR research_experience_years >= 0),
  FOREIGN KEY (appraisal_id) REFERENCES appraisals(id) ON DELETE CASCADE
);

CREATE TABLE student_mentoring (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  appraisal_id BIGINT NOT NULL UNIQUE,
  total_students_mentored INT NOT NULL DEFAULT 0,
  CHECK (total_students_mentored >= 0),
  FOREIGN KEY (appraisal_id) REFERENCES appraisals(id) ON DELETE CASCADE
);

CREATE TABLE teaching_courses (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  appraisal_id BIGINT NOT NULL,
  course_code VARCHAR(32) NOT NULL,
  course_name VARCHAR(160) NOT NULL,
  course_type VARCHAR(8) NOT NULL,
  program VARCHAR(40) NOT NULL,
  branch VARCHAR(40) NOT NULL,
  semester TINYINT NOT NULL,
  sections INT NOT NULL,
  hours_per_week DECIMAL(4,1) NOT NULL,
  pass_percentage DECIMAL(5,2) NOT NULL,
  phase1_feedback DECIMAL(5,2),
  phase2_feedback DECIMAL(5,2),
  INDEX idx_teaching_appraisal (appraisal_id),
  CHECK (course_type IN ('THEORY','LAB')),
  CHECK (semester BETWEEN 1 AND 12),
  CHECK (sections >= 0),
  CHECK (hours_per_week >= 0),
  CHECK (pass_percentage BETWEEN 0 AND 100),
  CHECK (phase1_feedback IS NULL OR phase1_feedback BETWEEN 0 AND 100),
  CHECK (phase2_feedback IS NULL OR phase2_feedback BETWEEN 0 AND 100),
  FOREIGN KEY (appraisal_id) REFERENCES appraisals(id) ON DELETE CASCADE
);

CREATE TABLE student_achievements (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  appraisal_id BIGINT NOT NULL,
  student_name VARCHAR(120) NOT NULL,
  roll_no VARCHAR(32) NOT NULL,
  achievement VARCHAR(300) NOT NULL,
  level VARCHAR(8) NOT NULL,
  month_year VARCHAR(7) NOT NULL,
  INDEX idx_ach_appraisal (appraisal_id),
  CHECK (level IN ('INST','STATE','NAT','INTL')),
  CHECK (month_year REGEXP '^[0-9]{4}-(0[1-9]|1[0-2])$'),
  FOREIGN KEY (appraisal_id) REFERENCES appraisals(id) ON DELETE CASCADE
);

CREATE TABLE student_projects (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  appraisal_id BIGINT NOT NULL,
  level VARCHAR(8) NOT NULL,
  title VARCHAR(300) NOT NULL,
  student_count INT NOT NULL,
  outcome VARCHAR(12) NOT NULL,
  INDEX idx_proj_appraisal (appraisal_id),
  CHECK (level IN ('DIPLOMA','UG','PG')),
  CHECK (student_count >= 0),
  CHECK (outcome IN ('PAPER','PATENT','PROTOTYPE','COMPETITION')),
  FOREIGN KEY (appraisal_id) REFERENCES appraisals(id) ON DELETE CASCADE
);

CREATE TABLE fdps (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  appraisal_id BIGINT NOT NULL,
  title VARCHAR(300) NOT NULL,
  mode VARCHAR(8) NOT NULL,
  institution_venue VARCHAR(200) NOT NULL,
  start_date DATE NOT NULL,
  end_date DATE NOT NULL,
  days INT NOT NULL,
  INDEX idx_fdp_appraisal (appraisal_id),
  CHECK (mode IN ('OFFLINE','ONLINE','BLENDED')),
  CHECK (end_date >= start_date),
  CHECK (days >= 1),
  FOREIGN KEY (appraisal_id) REFERENCES appraisals(id) ON DELETE CASCADE
);

CREATE TABLE certifications (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  appraisal_id BIGINT NOT NULL,
  platform VARCHAR(12) NOT NULL,
  title VARCHAR(300) NOT NULL,
  start_date DATE NOT NULL,
  end_date DATE NOT NULL,
  duration_weeks_hours VARCHAR(40),
  grade_score VARCHAR(40),
  INDEX idx_cert_appraisal (appraisal_id),
  CHECK (platform IN ('NPTEL','SWAYAM','COURSERA','OTHER')),
  CHECK (end_date >= start_date),
  FOREIGN KEY (appraisal_id) REFERENCES appraisals(id) ON DELETE CASCADE
);

CREATE TABLE administrative_roles (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  appraisal_id BIGINT NOT NULL,
  scope VARCHAR(12) NOT NULL,
  role VARCHAR(160) NOT NULL,
  description VARCHAR(1000),
  period VARCHAR(60) NOT NULL,
  INDEX idx_admin_appraisal (appraisal_id),
  CHECK (scope IN ('INSTITUTE','DEPARTMENT')),
  FOREIGN KEY (appraisal_id) REFERENCES appraisals(id) ON DELETE CASCADE
);

CREATE TABLE events (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  appraisal_id BIGINT NOT NULL,
  activity_type VARCHAR(80) NOT NULL,
  role VARCHAR(80) NOT NULL,
  title VARCHAR(300) NOT NULL,
  start_date DATE NOT NULL,
  end_date DATE NOT NULL,
  beneficiaries INT NOT NULL,
  INDEX idx_events_appraisal (appraisal_id),
  CHECK (end_date >= start_date),
  CHECK (beneficiaries >= 0),
  FOREIGN KEY (appraisal_id) REFERENCES appraisals(id) ON DELETE CASCADE
);

CREATE TABLE journal_publications (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  appraisal_id BIGINT NOT NULL,
  title VARCHAR(400) NOT NULL,
  author_position VARCHAR(40) NOT NULL,
  journal VARCHAR(200) NOT NULL,
  volume_issue_page VARCHAR(80),
  month_year VARCHAR(7) NOT NULL,
  indexing VARCHAR(16) NOT NULL,
  doi_issn VARCHAR(80),
  INDEX idx_journal_appraisal (appraisal_id),
  CHECK (indexing IN ('SCI_SCIE','SCOPUS','UGC_CARE_ABDC','OTHERS')),
  CHECK (month_year REGEXP '^[0-9]{4}-(0[1-9]|1[0-2])$'),
  FOREIGN KEY (appraisal_id) REFERENCES appraisals(id) ON DELETE CASCADE
);

CREATE TABLE conference_papers (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  appraisal_id BIGINT NOT NULL,
  title VARCHAR(400) NOT NULL,
  conference VARCHAR(200) NOT NULL,
  level VARCHAR(8) NOT NULL,
  month_year VARCHAR(7) NOT NULL,
  venue VARCHAR(200),
  doi_indexed_in VARCHAR(120),
  citations INT NOT NULL DEFAULT 0,
  INDEX idx_conf_appraisal (appraisal_id),
  CHECK (level IN ('NAT','INTL')),
  CHECK (month_year REGEXP '^[0-9]{4}-(0[1-9]|1[0-2])$'),
  CHECK (citations >= 0),
  FOREIGN KEY (appraisal_id) REFERENCES appraisals(id) ON DELETE CASCADE
);

CREATE TABLE research_metrics (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  appraisal_id BIGINT NOT NULL,
  platform VARCHAR(20) NOT NULL,
  total_publications INT NOT NULL DEFAULT 0,
  total_citations INT NOT NULL DEFAULT 0,
  h_index INT NOT NULL DEFAULT 0,
  i10_index INT NOT NULL DEFAULT 0,
  UNIQUE (appraisal_id, platform),
  CHECK (platform IN ('GOOGLE_SCHOLAR','SCOPUS','WEB_OF_SCIENCE')),
  CHECK (total_publications >= 0 AND total_citations >= 0 AND h_index >= 0 AND i10_index >= 0),
  FOREIGN KEY (appraisal_id) REFERENCES appraisals(id) ON DELETE CASCADE
);

CREATE TABLE research_scholars (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  appraisal_id BIGINT NOT NULL,
  name VARCHAR(120) NOT NULL,
  degree VARCHAR(8) NOT NULL,
  university_reg_no VARCHAR(120),
  status VARCHAR(12) NOT NULL,
  year SMALLINT NOT NULL,
  INDEX idx_scholar_appraisal (appraisal_id),
  CHECK (degree IN ('PHD','MTECH','MBA')),
  CHECK (status IN ('REGISTERED','SUBMITTED','AWARDED')),
  CHECK (year BETWEEN 1950 AND 2100),
  FOREIGN KEY (appraisal_id) REFERENCES appraisals(id) ON DELETE CASCADE
);

CREATE TABLE phd_progress (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  appraisal_id BIGINT NOT NULL UNIQUE,
  university_center VARCHAR(200),
  registration_year SMALLINT,
  stage VARCHAR(24),
  progress VARCHAR(1000),
  CHECK (stage IS NULL OR stage IN ('COURSE_WORK','COMPREHENSIVE_PROPOSAL','SYNOPSIS',
                                    'THESIS_SUBMITTED','VIVA_COMPLETED')),
  CHECK (registration_year IS NULL OR registration_year BETWEEN 1950 AND 2100),
  FOREIGN KEY (appraisal_id) REFERENCES appraisals(id) ON DELETE CASCADE
);

CREATE TABLE funded_projects (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  appraisal_id BIGINT NOT NULL,
  title VARCHAR(300) NOT NULL,
  role VARCHAR(8) NOT NULL,
  team VARCHAR(300),
  type VARCHAR(12) NOT NULL,
  funding_agency_client VARCHAR(200) NOT NULL,
  amount DECIMAL(14,2) NOT NULL,
  start_date DATE NOT NULL,
  end_date DATE NOT NULL,
  status VARCHAR(12) NOT NULL,
  year SMALLINT NOT NULL,
  INDEX idx_funded_appraisal (appraisal_id),
  CHECK (role IN ('PI','CO_PI')),
  CHECK (type IN ('RESEARCH','CONSULTANCY')),
  CHECK (status IN ('SANCTIONED','APPLIED')),
  CHECK (amount >= 0),
  CHECK (end_date >= start_date),
  CHECK (year BETWEEN 1950 AND 2100),
  FOREIGN KEY (appraisal_id) REFERENCES appraisals(id) ON DELETE CASCADE
);

CREATE TABLE patents_ipr (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  appraisal_id BIGINT NOT NULL,
  applicant_inventors VARCHAR(300) NOT NULL,
  title VARCHAR(300) NOT NULL,
  application_patent_no VARCHAR(80),
  type VARCHAR(12) NOT NULL,
  status VARCHAR(12) NOT NULL,
  record_date DATE NOT NULL,
  INDEX idx_patent_appraisal (appraisal_id),
  CHECK (type IN ('DESIGN','UTILITY','COPYRIGHT')),
  CHECK (status IN ('FILED','PUBLISHED','GRANTED')),
  FOREIGN KEY (appraisal_id) REFERENCES appraisals(id) ON DELETE CASCADE
);

CREATE TABLE books (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  appraisal_id BIGINT NOT NULL,
  authors VARCHAR(300) NOT NULL,
  title VARCHAR(300) NOT NULL,
  publisher VARCHAR(160) NOT NULL,
  isbn VARCHAR(24),
  month_year VARCHAR(7) NOT NULL,
  type VARCHAR(8) NOT NULL,
  INDEX idx_book_appraisal (appraisal_id),
  CHECK (type IN ('BOOK','CHAPTER')),
  CHECK (month_year REGEXP '^[0-9]{4}-(0[1-9]|1[0-2])$'),
  FOREIGN KEY (appraisal_id) REFERENCES appraisals(id) ON DELETE CASCADE
);

CREATE TABLE outreach (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  appraisal_id BIGINT NOT NULL,
  role VARCHAR(40) NOT NULL,                 -- the closed list printed under the form's Outreach table
  event_activity VARCHAR(300) NOT NULL,
  organization VARCHAR(200) NOT NULL,
  venue VARCHAR(200),
  event_date DATE NOT NULL,
  INDEX idx_outreach_appraisal (appraisal_id),
  CHECK (role IN ('CONFERENCE_SESSION_CHAIR','EXPERT_LECTURE_DELIVERED','RESOURCE_PERSON',
                  'EDITORIAL_BOARD_MEMBER','JOURNAL_REVIEWER','EXTERNAL_EXAMINER','EXTERNAL_THESIS_EVALUATED',
                  'VISITING_RESEARCHER','INDUSTRY_INTERACTION_MOU','INTERNATIONAL_CONFERENCE_ATTENDED','OTHERS')),
  FOREIGN KEY (appraisal_id) REFERENCES appraisals(id) ON DELETE CASCADE
);

CREATE TABLE memberships_awards (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  appraisal_id BIGINT NOT NULL,
  item VARCHAR(300) NOT NULL,
  awarding_body VARCHAR(200) NOT NULL,
  level VARCHAR(8) NOT NULL,
  year SMALLINT NOT NULL,
  INDEX idx_award_appraisal (appraisal_id),
  CHECK (level IN ('INST','STATE','NAT','INTL')),
  CHECK (year BETWEEN 1950 AND 2100),
  FOREIGN KEY (appraisal_id) REFERENCES appraisals(id) ON DELETE CASCADE
);

CREATE TABLE other_contributions (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  appraisal_id BIGINT NOT NULL UNIQUE,
  department_contribution TEXT,
  institute_contribution TEXT,
  FOREIGN KEY (appraisal_id) REFERENCES appraisals(id) ON DELETE CASCADE
);

-- Metadata only; file bytes live in private storage addressed by storage_key.
CREATE TABLE supporting_documents (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  appraisal_id BIGINT NOT NULL,
  category VARCHAR(40) NOT NULL,
  related_entity_type VARCHAR(40),
  related_entity_id BIGINT,
  original_name VARCHAR(255) NOT NULL,
  storage_key VARCHAR(120) NOT NULL UNIQUE,
  content_type VARCHAR(100) NOT NULL,
  size_bytes BIGINT NOT NULL,
  checksum VARCHAR(64) NOT NULL,
  uploaded_by BIGINT NOT NULL,
  uploaded_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  INDEX idx_doc_appraisal (appraisal_id),
  CHECK (size_bytes > 0),
  FOREIGN KEY (appraisal_id) REFERENCES appraisals(id),
  FOREIGN KEY (uploaded_by) REFERENCES users(id)
);
