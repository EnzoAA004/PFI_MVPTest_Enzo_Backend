ALTER TABLE domain_run_artifacts
    ADD COLUMN IF NOT EXISTS storage_status TEXT NOT NULL DEFAULT 'upstream_only',
    ADD COLUMN IF NOT EXISTS storage_kind TEXT NULL,
    ADD COLUMN IF NOT EXISTS size_bytes BIGINT NULL,
    ADD COLUMN IF NOT EXISTS sha256 TEXT NULL;

DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM pg_constraint WHERE conname = 'chk_domain_run_artifacts_storage_status'
    ) THEN
        ALTER TABLE domain_run_artifacts
            ADD CONSTRAINT chk_domain_run_artifacts_storage_status
            CHECK (storage_status IN ('stored', 'upstream_only', 'missing', 'rejected'));
    END IF;
END $$;

CREATE TABLE IF NOT EXISTS domain_run_asset_payloads (
    artifact_id UUID PRIMARY KEY REFERENCES domain_run_artifacts(id) ON DELETE CASCADE,
    content BYTEA NOT NULL,
    sha256 TEXT NOT NULL,
    size_bytes BIGINT NOT NULL,
    storage_kind TEXT NOT NULL DEFAULT 'postgres_bytea',
    stored_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CHECK (size_bytes > 0),
    CHECK (size_bytes <= 5242880),
    CHECK (length(trim(sha256)) > 0)
);

CREATE INDEX IF NOT EXISTS idx_domain_run_asset_payloads_stored_at
    ON domain_run_asset_payloads(stored_at);
