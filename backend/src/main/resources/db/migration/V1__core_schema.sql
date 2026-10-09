-- Core identity, configuration, appraisal lifecycle, review and audit.
-- Section (Part B) tables live in V3. Enumerated values are enforced with CHECK constraints
-- (MySQL 8.0.16+) as defence in depth behind application validation.

CREATE TABLE departments (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  code VARCHAR(16) NOT NULL UNIQUE,
  name VARCHAR(120) NOT NULL,
  active BOOLEAN NOT NULL DEFAULT TRUE
);

CREATE TABLE academic_years (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  name VARCHAR(16) NOT NULL UNIQUE,          -- e.g. 2026-27
  start_date DATE NOT NULL,
  end_date DATE NOT NULL,
  active BOOLEAN NOT NULL DEFAULT TRUE,      -- open for new appraisals
  CHECK (end_date > start_date),
  CHECK (name REGEXP '^[0-9]{4}-[0-9]{2}$')
);

CREATE TABLE cadres (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  code VARCHAR(32) NOT NULL UNIQUE,
  name VARCHAR(80) NOT NULL,
  active BOOLEAN NOT NULL DEFAULT TRUE
);

CREATE TABLE users (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  email VARCHAR(190) NOT NULL UNIQUE,        -- case-insensitive collation
  password_hash VARCHAR(100),                -- NULL for externally-managed identities (future SSO)
  role VARCHAR(16) NOT NULL,
  status VARCHAR(16) NOT NULL DEFAULT 'ACTIVE',
  created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  CHECK (role IN ('FACULTY','HOD','PRINCIPAL','ADMIN')),
  CHECK (status IN ('ACTIVE','DISABLED'))
);

CREATE TABLE faculty_profiles (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  user_id BIGINT NOT NULL UNIQUE,            -- the college e-mail ID lives on users.email
  employee_id VARCHAR(32) NOT NULL UNIQUE,
  name VARCHAR(120) NOT NULL,
  contact_no VARCHAR(20),
  department_id BIGINT NOT NULL,
  cadre_id BIGINT NOT NULL,
  qualification VARCHAR(160),
  specialization VARCHAR(160),
  phd_status VARCHAR(16) NOT NULL DEFAULT 'NOT_APPLICABLE',
  joining_date_institution DATE,
  joining_date_designation DATE,
  teaching_experience_years DECIMAL(4,1),
  industry_experience_years DECIMAL(4,1),
  research_experience_years DECIMAL(4,1),
  orcid VARCHAR(40),
  scopus_id VARCHAR(40),
  google_scholar_id VARCHAR(60),
  vidwan_id VARCHAR(40),
  INDEX idx_profile_department (department_id),
  CHECK (phd_status IN ('AWARDED','PURSUING','NOT_APPLICABLE')),
  CHECK (teaching_experience_years IS NULL OR teaching_experience_years >= 0),
  CHECK (industry_experience_years IS NULL OR industry_experience_years >= 0),
  CHECK (research_experience_years IS NULL OR research_experience_years >= 0),
  FOREIGN KEY (user_id) REFERENCES users(id),
  FOREIGN KEY (department_id) REFERENCES departments(id),
  FOREIGN KEY (cadre_id) REFERENCES cadres(id)
);

-- HoD <-> department assignment (only an assigned HoD may review a department's appraisals).
CREATE TABLE hod_assignments (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  user_id BIGINT NOT NULL,
  department_id BIGINT NOT NULL,
  UNIQUE (user_id, department_id),
  FOREIGN KEY (user_id) REFERENCES users(id),
  FOREIGN KEY (department_id) REFERENCES departments(id)
);

-- Scoring policy: maximum marks per cadre, versioned per academic year.
-- New appraisals use the highest active version; existing appraisals keep the one they were created with.
CREATE TABLE scoring_policies (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  academic_year_id BIGINT NOT NULL,
  cadre_id BIGINT NOT NULL,
  version INT NOT NULL,
  active BOOLEAN NOT NULL DEFAULT TRUE,
  created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  UNIQUE (academic_year_id, cadre_id, version),
  CHECK (version >= 1),
  FOREIGN KEY (academic_year_id) REFERENCES academic_years(id),
  FOREIGN KEY (cadre_id) REFERENCES cadres(id)
);

CREATE TABLE scoring_policy_criteria (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  policy_id BIGINT NOT NULL,
  criterion VARCHAR(48) NOT NULL,
  max_marks INT NOT NULL,
  calculation_mode VARCHAR(16) NOT NULL DEFAULT 'SELF_ENTERED', -- activity-to-score formulas are unconfirmed
  configuration_json JSON NULL,
  UNIQUE (policy_id, criterion),
  CHECK (max_marks >= 0),
  CHECK (calculation_mode IN ('SELF_ENTERED','FORMULA')),
  FOREIGN KEY (policy_id) REFERENCES scoring_policies(id)
);

CREATE TABLE appraisals (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  faculty_id BIGINT NOT NULL,
  academic_year_id BIGINT NOT NULL,
  scoring_policy_id BIGINT NOT NULL,
  status VARCHAR(24) NOT NULL DEFAULT 'DRAFT',
  submitted_at TIMESTAMP NULL,               -- time of the most recent (re)submission
  final_approved_at TIMESTAMP NULL,
  declaration_place VARCHAR(80) NULL,        -- the form's Declaration: place and date, given at (re)submission
  declared_at TIMESTAMP NULL,
  created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  UNIQUE (faculty_id, academic_year_id),     -- one per year; pending stakeholder confirmation (Q6)
  INDEX idx_appraisals_status (status),
  INDEX idx_appraisals_year (academic_year_id),
  CHECK (status IN ('DRAFT','SUBMITTED','HOD_REVIEW','HOD_RETURNED','HOD_APPROVED',
                    'PRINCIPAL_REVIEW','PRINCIPAL_RETURNED','APPROVED')),
  CHECK ((status = 'APPROVED') = (final_approved_at IS NOT NULL)),
  CHECK ((declared_at IS NULL) = (declaration_place IS NULL)),
  FOREIGN KEY (faculty_id) REFERENCES faculty_profiles(id),
  FOREIGN KEY (academic_year_id) REFERENCES academic_years(id),
  FOREIGN KEY (scoring_policy_id) REFERENCES scoring_policies(id)
);

CREATE TABLE appraisal_scores (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  appraisal_id BIGINT NOT NULL,
  criterion VARCHAR(48) NOT NULL,
  max_marks INT NOT NULL,                    -- snapshot of the policy at creation
  self_score DECIMAL(5,2),
  review_score DECIMAL(5,2),
  remarks VARCHAR(500),
  UNIQUE (appraisal_id, criterion),
  CHECK (self_score IS NULL OR (self_score >= 0 AND self_score <= max_marks)),
  CHECK (review_score IS NULL OR (review_score >= 0 AND review_score <= max_marks)),
  FOREIGN KEY (appraisal_id) REFERENCES appraisals(id)
);

CREATE TABLE review_actions (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  appraisal_id BIGINT NOT NULL,
  actor_id BIGINT NOT NULL,
  actor_role VARCHAR(16) NOT NULL,
  action VARCHAR(24) NOT NULL,
  from_status VARCHAR(24) NOT NULL,
  to_status VARCHAR(24) NOT NULL,
  comment VARCHAR(2000),
  created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  INDEX idx_review_appraisal (appraisal_id),
  CHECK (from_status <> to_status),
  FOREIGN KEY (appraisal_id) REFERENCES appraisals(id),
  FOREIGN KEY (actor_id) REFERENCES users(id)
);

-- No foreign key on actor_id/entity_id: audit history must survive any later data change.
CREATE TABLE audit_logs (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  actor_id BIGINT NULL,
  action VARCHAR(48) NOT NULL,
  entity_type VARCHAR(48) NOT NULL,
  entity_id BIGINT NULL,
  metadata JSON NULL,
  created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  INDEX idx_audit_entity (entity_type, entity_id),
  INDEX idx_audit_actor (actor_id)
);

-- History tables are append-only. (TRUNCATE bypasses triggers; production DB users must not hold DROP/TRUNCATE.)
CREATE TRIGGER audit_logs_no_update BEFORE UPDATE ON audit_logs FOR EACH ROW
  SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'audit_logs is append-only';
CREATE TRIGGER audit_logs_no_delete BEFORE DELETE ON audit_logs FOR EACH ROW
  SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'audit_logs is append-only';
CREATE TRIGGER review_actions_no_update BEFORE UPDATE ON review_actions FOR EACH ROW
  SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'review_actions is append-only';
CREATE TRIGGER review_actions_no_delete BEFORE DELETE ON review_actions FOR EACH ROW
  SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'review_actions is append-only';
