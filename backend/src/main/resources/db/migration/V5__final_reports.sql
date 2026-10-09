-- The official PDF of an APPROVED appraisal, generated once and kept exactly as issued.
-- Drafts are produced on demand and are never stored. One row per appraisal; rows cannot be changed or removed.

CREATE TABLE appraisal_reports (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  appraisal_id BIGINT NOT NULL UNIQUE,
  storage_key VARCHAR(120) NOT NULL UNIQUE,
  checksum VARCHAR(64) NOT NULL,
  size_bytes BIGINT NOT NULL,
  generated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  CHECK (checksum REGEXP '^[0-9a-f]{64}$'),
  CHECK (size_bytes > 0),
  FOREIGN KEY (appraisal_id) REFERENCES appraisals(id)
);

CREATE TRIGGER appraisal_reports_no_update BEFORE UPDATE ON appraisal_reports FOR EACH ROW
  SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'appraisal_reports is append-only';
CREATE TRIGGER appraisal_reports_no_delete BEFORE DELETE ON appraisal_reports FOR EACH ROW
  SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'appraisal_reports is append-only';
