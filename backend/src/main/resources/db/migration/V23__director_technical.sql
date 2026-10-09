-- The Director Technical: a second account at the Principal's level of the approval chain.
-- The chain stays  faculty -> Head of the Department -> Principal, and no status is added: an appraisal the HoD has
-- forwarded (HOD_APPROVED) may be taken up by the Principal or by the Director Technical, and whichever of them gives the
-- approval, it is final. Who acted is kept in review_actions (actor_role DIRECTOR, actions START_DIRECTOR_REVIEW and
-- DIRECTOR_APPROVE), and the report prints a box for each.
--
-- Only the list of roles a user may have changes. Accounts of the Dean and Vice Principal, withdrawn in V8, stay closed.

ALTER TABLE users DROP CHECK chk_users_role;
ALTER TABLE users ADD CONSTRAINT chk_users_role CHECK (
    role IN ('FACULTY','HOD','PRINCIPAL','DIRECTOR','ADMIN')
    OR (role IN ('DEAN','VICE_PRINCIPAL') AND status = 'DISABLED'));
