-- The Head of the Department may correct a message while the appraisal is still under their review (it cannot be deleted).
-- edited_at is when the text was last changed, so the faculty member can be told; changing the text also clears read_at,
-- so the faculty member is shown the new wording as unread.
ALTER TABLE appraisal_messages ADD COLUMN edited_at TIMESTAMP NULL AFTER created_at;
