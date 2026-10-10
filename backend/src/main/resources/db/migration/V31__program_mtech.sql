-- M.Tech is added to the programs a course can belong to. Its branches are departments the college already has, so the
-- branch constraint stays as it is.
ALTER TABLE teaching_courses DROP CHECK teaching_courses_program_chk;
ALTER TABLE teaching_courses
    ADD CONSTRAINT teaching_courses_program_chk CHECK (program IN ('B_TECH','M_TECH','DIPLOMA','MBA','PHARMACY'));
