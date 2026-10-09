-- What the administrator needs to run the system, kept in the database so it survives a restore with everything else.
--
--   import_history   each CSV import: when, who, how many rows and accounts, and the list of problems when it was refused
--   restore_tests    each time the administrator tried a backup on a spare machine, and whether it worked
--   handover_notes   who looks after the server and whom to call, entered by an administrator

CREATE TABLE import_history (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  admin_id BIGINT NOT NULL,
  imported_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  rows_in_file INT NOT NULL,                  -- accounts the file described (0 when the file itself could not be read)
  created_count INT NOT NULL,                 -- all of them, or none: an import is all or nothing
  rejected_rows INT NOT NULL,                 -- rows with at least one problem
  outcome VARCHAR(12) NOT NULL,
  error_report MEDIUMTEXT NULL,               -- CSV: line, column, problem
  INDEX idx_import_history_time (imported_at),
  CHECK (outcome IN ('CREATED','REJECTED')),
  CHECK (rows_in_file >= 0 AND created_count >= 0 AND rejected_rows >= 0),
  FOREIGN KEY (admin_id) REFERENCES users(id)
);

CREATE TABLE restore_tests (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  tested_on DATE NOT NULL,
  result VARCHAR(8) NOT NULL,
  notes VARCHAR(500) NULL,
  recorded_by BIGINT NOT NULL,
  recorded_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  INDEX idx_restore_tests_date (tested_on),
  CHECK (result IN ('PASSED','FAILED')),
  FOREIGN KEY (recorded_by) REFERENCES users(id)
);

CREATE TABLE handover_notes (
  note_key VARCHAR(40) NOT NULL PRIMARY KEY,
  note_value VARCHAR(1000) NOT NULL,
  updated_by BIGINT NOT NULL,
  updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  FOREIGN KEY (updated_by) REFERENCES users(id)
);
