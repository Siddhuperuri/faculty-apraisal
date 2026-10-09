-- The approval chain becomes  faculty -> Head of the Department -> Principal.
-- The Dean and Vice Principal levels added in V7 are withdrawn, and so is every "return to the faculty member" step.
--
-- Nothing recorded is lost. review_actions and audit_logs are append-only and keep naming the old steps, statuses and
-- roles exactly as they happened; dean_assignments is left in place, unused, as the record of who covered what.
-- Only the two things that would otherwise be stranded are moved, each with an audit entry:
--
--   * an appraisal standing at a withdrawn status goes to the nearest status of the new chain:
--       returned to its author (by anyone)        -> DRAFT         (it was open for correction; it is submitted again)
--       with, or passed by, the Dean or the VP    -> HOD_APPROVED  (the HoD had forwarded it; it now waits for the Principal)
--   * accounts of the two withdrawn roles are closed. They keep their role for the record and can never be re-opened
--     (the constraint below); the application does not let them sign in.

INSERT INTO audit_logs (actor_id, action, entity_type, entity_id, metadata)
SELECT NULL, 'APPRAISAL_STATUS_MIGRATED', 'APPRAISAL', id,
       JSON_OBJECT('from', status,
                   'to', IF(status IN ('HOD_RETURNED','DEAN_RETURNED','VP_RETURNED','PRINCIPAL_RETURNED'), 'DRAFT', 'HOD_APPROVED'))
FROM appraisals
WHERE status IN ('HOD_RETURNED','DEAN_RETURNED','VP_RETURNED','PRINCIPAL_RETURNED',
                 'DEAN_REVIEW','DEAN_APPROVED','VP_REVIEW','VP_APPROVED');

UPDATE appraisals SET status = 'DRAFT', updated_at = updated_at
WHERE status IN ('HOD_RETURNED','DEAN_RETURNED','VP_RETURNED','PRINCIPAL_RETURNED');

UPDATE appraisals SET status = 'HOD_APPROVED', updated_at = updated_at
WHERE status IN ('DEAN_REVIEW','DEAN_APPROVED','VP_REVIEW','VP_APPROVED');

INSERT INTO audit_logs (actor_id, action, entity_type, entity_id, metadata)
SELECT NULL, 'USER_ROLE_WITHDRAWN', 'USER', id, JSON_OBJECT('email', email, 'role', role)
FROM users WHERE role IN ('DEAN','VICE_PRINCIPAL');

UPDATE users SET status = 'DISABLED' WHERE role IN ('DEAN','VICE_PRINCIPAL');

ALTER TABLE appraisals DROP CHECK chk_appraisals_status;
ALTER TABLE appraisals ADD CONSTRAINT chk_appraisals_status CHECK (status IN (
    'DRAFT','SUBMITTED','HOD_REVIEW','HOD_APPROVED','PRINCIPAL_REVIEW','APPROVED'));

ALTER TABLE users DROP CHECK chk_users_role;
ALTER TABLE users ADD CONSTRAINT chk_users_role CHECK (
    role IN ('FACULTY','HOD','PRINCIPAL','ADMIN')
    OR (role IN ('DEAN','VICE_PRINCIPAL') AND status = 'DISABLED'));
