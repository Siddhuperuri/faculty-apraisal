-- A student project guided may have no outcome (no paper, patent, prototype or competition): add "None".
ALTER TABLE student_projects DROP CHECK student_projects_chk_3;
ALTER TABLE student_projects ADD CONSTRAINT student_projects_outcome_chk
    CHECK (outcome IN ('PAPER','PATENT','PROTOTYPE','COMPETITION','NONE'));
