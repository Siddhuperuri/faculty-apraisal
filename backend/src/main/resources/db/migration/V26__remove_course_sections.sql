-- The teaching-courses form no longer asks for the number of sections of a course. Courses already entered keep
-- everything else; only that one value is dropped.
ALTER TABLE teaching_courses DROP CHECK teaching_courses_chk_3;
ALTER TABLE teaching_courses DROP COLUMN sections;
