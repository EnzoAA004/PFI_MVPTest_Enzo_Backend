-- BE-007: professional review workflow for persisted multiplanar runs.
-- Adds observed status and minimal measurement correction snapshots.

DO $$
DECLARE
    constraint_name TEXT;
BEGIN
    SELECT c.conname INTO constraint_name
    FROM pg_constraint c
    JOIN pg_class t ON t.oid = c.conrelid
    WHERE t.relname = 'domain_study_runs'
      AND c.contype = 'c'
      AND pg_get_constraintdef(c.oid) LIKE '%review_status%'
    LIMIT 1;

    IF constraint_name IS NOT NULL THEN
        EXECUTE format('ALTER TABLE domain_study_runs DROP CONSTRAINT %I', constraint_name);
    END IF;
END $$;

ALTER TABLE domain_study_runs
ADD CONSTRAINT chk_domain_study_runs_review_status
CHECK (review_status IN ('pending', 'accepted', 'observed', 'rejected', 'edited'));

CREATE TABLE IF NOT EXISTS domain_review_corrections (
    id UUID PRIMARY KEY,
    study_run_id UUID NOT NULL REFERENCES domain_study_runs(id) ON DELETE CASCADE,
    measurement_id TEXT NOT NULL,
    label TEXT NOT NULL DEFAULT '',
    before_value JSONB NOT NULL DEFAULT '{}'::jsonb,
    after_value JSONB NOT NULL DEFAULT '{}'::jsonb,
    comment TEXT NOT NULL DEFAULT '',
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX IF NOT EXISTS idx_domain_review_corrections_run ON domain_review_corrections(study_run_id);
