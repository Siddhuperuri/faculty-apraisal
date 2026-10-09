-- Messages from the Head of the Department to the faculty member about an appraisal that is under review (for example to ask
-- them to come and discuss a query before it can be approved). A message does not change the appraisal or send it back: it
-- is only a note the faculty member is shown. There is no way to edit or delete one. read_at is set when the faculty member
-- has opened it, so the Head of the Department can see that it was seen.
CREATE TABLE appraisal_messages (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  appraisal_id BIGINT NOT NULL,
  sender_id BIGINT NOT NULL,
  sender_role VARCHAR(16) NOT NULL,
  body VARCHAR(2000) NOT NULL,
  created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  read_at TIMESTAMP NULL,
  INDEX idx_messages_appraisal (appraisal_id, id),
  CHECK (CHAR_LENGTH(TRIM(body)) > 0),
  CHECK (sender_role IN ('HOD')),
  FOREIGN KEY (appraisal_id) REFERENCES appraisals(id),
  FOREIGN KEY (sender_id) REFERENCES users(id)
);
