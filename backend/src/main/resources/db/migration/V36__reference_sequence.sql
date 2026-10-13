-- The order of a dropdown list is now changed one place at a time (Up / Down in the administration page), so every list
-- needs a sequence that is unique and stable. Existing values keep their relative order (sort_order, then id) and are
-- numbered 10, 20, 30, ... within their list; codes, wording, state and the records that hold them are untouched.
UPDATE reference_values v
JOIN (
  SELECT id, ROW_NUMBER() OVER (PARTITION BY list_code ORDER BY sort_order, id) * 10 AS seq
  FROM reference_values
) n ON n.id = v.id
SET v.sort_order = n.seq;

ALTER TABLE reference_values
  ADD CONSTRAINT reference_values_sequence_unique UNIQUE (list_code, sort_order);
