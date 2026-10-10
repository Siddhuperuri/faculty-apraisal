-- While the Head of the Department is reviewing an appraisal and has sent the faculty member a message (a query), the
-- faculty member may correct the appraisal and send it again; the appraisal then goes back to the "submitted" step and the
-- Head of the Department begins the review again. The step is recorded in review_actions as RESUBMIT.
--
-- answered_at is when the faculty member sent the appraisal again after the message. A message with no answered_at is an
-- open query: it is what keeps the appraisal editable for the faculty member, and what the lists show as "query raised".
-- A query answered by sending the appraisal again no longer counts when the Head of the Department begins the next review.
ALTER TABLE appraisal_messages ADD COLUMN answered_at TIMESTAMP NULL AFTER read_at;
UPDATE appraisal_messages SET answered_at = CURRENT_TIMESTAMP
WHERE appraisal_id IN (SELECT id FROM appraisals WHERE status <> 'HOD_REVIEW');
