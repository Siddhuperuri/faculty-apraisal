-- B.Tech branches CAI, AIM and CSDS are added to the branches a course can be taught in.
ALTER TABLE teaching_courses DROP CHECK teaching_courses_branch_chk;
ALTER TABLE teaching_courses
    ADD CONSTRAINT teaching_courses_branch_chk CHECK (branch IN ('CSE','AIML','CAI','AIM','CSDS','ECE','EEE','ME','CE','BSH','MBA',
        'PHARMACEUTICS','PHARMACEUTICAL_CHEMISTRY','PHARMACOLOGY','PHARMACOGNOSY','PHARMACY_PRACTICE'));
