-- Every earlier wording of a message that its writer has since edited, oldest first. A row is written, in the same
-- transaction as the edit, with the text that the edit replaced. Append-only, like the review history: nothing here is
-- changed or removed.
CREATE TABLE appraisal_message_versions (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  message_id BIGINT NOT NULL,
  body VARCHAR(2000) NOT NULL,
  written_at TIMESTAMP NOT NULL,                              -- when this wording was written (sent, or saved by an earlier edit)
  replaced_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,   -- when an edit replaced it
  INDEX idx_message_versions (message_id, id),
  FOREIGN KEY (message_id) REFERENCES appraisal_messages(id)
);

CREATE TRIGGER appraisal_message_versions_no_update BEFORE UPDATE ON appraisal_message_versions FOR EACH ROW
  SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'appraisal_message_versions is append-only';
CREATE TRIGGER appraisal_message_versions_no_delete BEFORE DELETE ON appraisal_message_versions FOR EACH ROW
  SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'appraisal_message_versions is append-only';
