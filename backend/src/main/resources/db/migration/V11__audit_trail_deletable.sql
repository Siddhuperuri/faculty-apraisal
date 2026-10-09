-- An administrator may now delete audit entries (one, or every entry matching a filter, or all of them).
-- Entries still cannot be edited: only the delete lock is lifted. Each deletion writes one new entry (AUDIT_DELETED)
-- saying who deleted how many, and that entry is itself part of the trail.
-- review_actions and appraisal_reports stay append-only.

DROP TRIGGER audit_logs_no_delete;
