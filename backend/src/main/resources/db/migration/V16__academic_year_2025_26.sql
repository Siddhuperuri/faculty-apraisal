-- The academic year the college appraises is 2025-26, not the 2026-27 that V2 seeded. Renamed in place so that
-- appraisals and scoring policies already attached to it keep working; the guard makes this a no-op where an
-- administrator has already renamed or replaced the year.

UPDATE academic_years
SET name = '2025-26', start_date = '2025-06-01', end_date = '2026-05-31'
WHERE name = '2026-27'
  AND NOT EXISTS (SELECT 1 FROM (SELECT id FROM academic_years WHERE name = '2025-26') AS existing);
