-- Two more levels between the Head of the Department and the Principal: the Dean and the Vice Principal.
-- The approval chain becomes  faculty -> HoD -> Dean -> Vice Principal -> Principal.
--
--   * users.role accepts DEAN and VICE_PRINCIPAL;
--   * appraisals.status accepts the six new review states (DEAN_REVIEW / DEAN_RETURNED / DEAN_APPROVED and the same for VP);
--   * a Dean covers the departments an administrator assigns (like an HoD); the Vice Principal covers the whole college.
--
-- MySQL named the original unnamed CHECK constraints users_chk_1 and appraisals_chk_1 (the first one declared on each table).
-- An appraisal already waiting at HOD_APPROVED now waits for a Dean instead of the Principal.

ALTER TABLE users
  DROP CHECK users_chk_1,
  ADD CONSTRAINT chk_users_role CHECK (role IN ('FACULTY','HOD','DEAN','VICE_PRINCIPAL','PRINCIPAL','ADMIN'));

ALTER TABLE appraisals
  DROP CHECK appraisals_chk_1,
  ADD CONSTRAINT chk_appraisals_status CHECK (status IN (
      'DRAFT','SUBMITTED','HOD_REVIEW','HOD_RETURNED','HOD_APPROVED',
      'DEAN_REVIEW','DEAN_RETURNED','DEAN_APPROVED',
      'VP_REVIEW','VP_RETURNED','VP_APPROVED',
      'PRINCIPAL_REVIEW','PRINCIPAL_RETURNED','APPROVED'));

CREATE TABLE dean_assignments (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  user_id BIGINT NOT NULL,
  department_id BIGINT NOT NULL,
  UNIQUE (user_id, department_id),
  FOREIGN KEY (user_id) REFERENCES users(id),
  FOREIGN KEY (department_id) REFERENCES departments(id)
);
