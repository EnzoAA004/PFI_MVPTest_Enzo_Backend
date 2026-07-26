-- P8-E1: de-identified subject reference lookup for longitudinal history.
-- Historical studies may keep subject_ref NULL; this index is intentionally non-unique.

CREATE INDEX IF NOT EXISTS idx_domain_studies_subject_ref
ON domain_studies (lower(subject_ref))
WHERE subject_ref IS NOT NULL;
