-- The declaration no longer asks for a place: a tick and the server-recorded date are enough.
-- The place already given on appraisals that were submitted is dropped with the column; the date stays.
-- Reports already issued are stored files and are not affected.

ALTER TABLE appraisals DROP CHECK appraisals_chk_3;
ALTER TABLE appraisals DROP COLUMN declaration_place;
