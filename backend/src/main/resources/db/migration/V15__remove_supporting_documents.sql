-- Section 12 (Supporting Documents) is no longer part of the form: the college does not collect evidence files in the
-- system. The table held only metadata about uploaded files (V3, V4); nothing else refers to it.
-- Files already uploaded stay in the private storage directory, where they are no longer reachable from the application.
-- Issued reports share that directory and are kept in appraisal_reports, which this does not touch.

DROP TABLE supporting_documents;
