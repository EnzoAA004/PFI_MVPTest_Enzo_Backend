-- P8-A: persisted study metadata for database-backed worklist.
-- Existing rows keep nullable values; no demo identifiers are backfilled.

ALTER TABLE domain_studies
    ADD COLUMN IF NOT EXISTS subject_ref TEXT NULL,
    ADD COLUMN IF NOT EXISTS study_date DATE NULL,
    ADD COLUMN IF NOT EXISTS modality TEXT NULL,
    ADD COLUMN IF NOT EXISTS description TEXT NULL,
    ADD COLUMN IF NOT EXISTS review_priority TEXT NOT NULL DEFAULT 'medium';

DO $$
BEGIN
    ALTER TABLE domain_studies
        ADD CONSTRAINT chk_domain_studies_review_priority
        CHECK (review_priority IN ('medium', 'high', 'low'));
EXCEPTION
    WHEN duplicate_object THEN NULL;
END $$;

CREATE INDEX IF NOT EXISTS idx_domain_studies_updated_at ON domain_studies(updated_at DESC);
