-- Supporting documents (item 11 of the form: "Supporting documents enclosed").
-- The categories are the form's eleven checkboxes; "OTHERS" carries a description (the form's "Others: ____").
-- Only metadata lives here. File bytes live in private storage under an opaque key that is never shown to users.
-- Linking a document to a single record was left out on purpose: the form works at the level of document type.

ALTER TABLE supporting_documents
  DROP COLUMN related_entity_type,
  DROP COLUMN related_entity_id,
  ADD COLUMN note VARCHAR(300) NULL AFTER category,
  ADD INDEX idx_doc_category (appraisal_id, category),
  ADD INDEX idx_doc_checksum (appraisal_id, checksum),
  ADD CONSTRAINT chk_doc_category CHECK (category IN (
      'COURSE_FILES_RESULT_ANALYSIS','STUDENT_FEEDBACK_REPORTS','MENTORING_RECORDS','PUBLICATION_PROOFS',
      'PROJECT_SANCTION_LETTERS','PATENT_BOOK_PROOFS','FDP_COURSE_CERTIFICATES','ROLE_APPOINTMENT_ORDERS',
      'EVENT_REPORTS','AWARDS_MEMBERSHIP_PROOFS','OTHERS')),
  ADD CONSTRAINT chk_doc_type CHECK (content_type IN ('application/pdf','image/png','image/jpeg')),
  ADD CONSTRAINT chk_doc_checksum CHECK (checksum REGEXP '^[0-9a-f]{64}$'),
  ADD CONSTRAINT chk_doc_others_note CHECK (category <> 'OTHERS' OR (note IS NOT NULL AND CHAR_LENGTH(note) > 0));
