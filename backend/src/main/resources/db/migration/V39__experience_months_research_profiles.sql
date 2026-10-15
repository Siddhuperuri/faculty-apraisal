-- 2026-10-14: General Information and the revoke trail.
--
-- Nothing recorded is lost in meaning: values already stored are converted, and every change below is explained.

-- 1. Teaching experience is given as whole years (0 to 40) and whole months (0 to 11) instead of one decimal number. A
--    value already saved is split: 6.5 becomes 6 years and 6 months. A value of 40 years or more (the old limit was
--    1,000) is held at 40 years and no months, the new limit.
ALTER TABLE general_information
  ADD COLUMN teaching_experience_months TINYINT UNSIGNED NULL AFTER teaching_experience_years;

UPDATE general_information
SET teaching_experience_months = CASE
      WHEN teaching_experience_years >= 40 THEN 0
      ELSE LEAST(11, ROUND((teaching_experience_years - FLOOR(teaching_experience_years)) * 12))
    END
WHERE teaching_experience_years IS NOT NULL;

UPDATE general_information
SET teaching_experience_years = LEAST(40, FLOOR(teaching_experience_years))
WHERE teaching_experience_years IS NOT NULL;

ALTER TABLE general_information MODIFY teaching_experience_years SMALLINT UNSIGNED NULL;

ALTER TABLE general_information
  ADD CONSTRAINT general_information_teaching_years_chk CHECK (teaching_experience_years IS NULL OR teaching_experience_years <= 40),
  ADD CONSTRAINT general_information_teaching_months_chk CHECK (teaching_experience_months IS NULL OR teaching_experience_months <= 11);

-- 2. Research profile IDs (ORCID, Scopus, Google Scholar, Vidwan) are added one at a time, each chosen from a dropdown and
--    given its own ID or link, instead of being typed into one text box. Each kind can be given once.
INSERT INTO reference_lists (code, name, description, parent_list, kind) VALUES
  ('RESEARCH_PROFILE', 'Research profiles', 'General Information: the kinds of research profile ID a faculty member can add', NULL, 'CODE');
INSERT INTO reference_values (list_code, code, parent_code, label, report_label, sort_order, locked) VALUES
  ('RESEARCH_PROFILE', 'ORCID', '', 'ORCID', NULL, 10, TRUE),
  ('RESEARCH_PROFILE', 'SCOPUS', '', 'Scopus', NULL, 20, TRUE),
  ('RESEARCH_PROFILE', 'GOOGLE_SCHOLAR', '', 'Google Scholar', NULL, 30, TRUE),
  ('RESEARCH_PROFILE', 'VIDWAN', '', 'Vidwan', NULL, 40, TRUE);

CREATE TABLE research_profiles (
  id           BIGINT       NOT NULL AUTO_INCREMENT PRIMARY KEY,
  appraisal_id BIGINT       NOT NULL,
  platform     VARCHAR(30)  NOT NULL,
  profile_id   VARCHAR(120) NOT NULL,
  CONSTRAINT research_profiles_appraisal_fk FOREIGN KEY (appraisal_id) REFERENCES appraisals (id) ON DELETE CASCADE,
  CONSTRAINT research_profiles_unique UNIQUE (appraisal_id, platform)
);

-- An ORCID iD that was typed into the old single text box becomes an ORCID row. Anything else in that text stays in
-- research_ids, which the printed form still shows when an appraisal has no profile rows (it is no longer edited).
INSERT INTO research_profiles (appraisal_id, platform, profile_id)
SELECT gi.appraisal_id, 'ORCID',
       REGEXP_SUBSTR(gi.research_ids, '[0-9]{4}-[0-9]{4}-[0-9]{4}-[0-9]{3}[0-9Xx]')
FROM general_information gi
WHERE gi.research_ids IS NOT NULL
  AND REGEXP_LIKE(gi.research_ids, '[0-9]{4}-[0-9]{4}-[0-9]{4}-[0-9]{3}[0-9Xx]');
