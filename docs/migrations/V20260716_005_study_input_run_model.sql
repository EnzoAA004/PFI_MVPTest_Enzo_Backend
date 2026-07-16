-- BE-005: Study/InputResource/StudyRun model.
-- No blobs are stored in DB: input_id points to the AI Module input registry and assets stores metadata/asset refs only.

CREATE TABLE IF NOT EXISTS domain_studies (
    id UUID PRIMARY KEY,
    case_id TEXT NOT NULL UNIQUE,
    status TEXT NOT NULL DEFAULT 'created',
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE IF NOT EXISTS domain_input_resources (
    id UUID PRIMARY KEY,
    study_id UUID NOT NULL REFERENCES domain_studies(id) ON DELETE CASCADE,
    plane TEXT NOT NULL CHECK (plane IN ('sagittal', 'axial')),
    input_id TEXT NOT NULL UNIQUE,
    format TEXT NOT NULL,
    size_bytes BIGINT NOT NULL CHECK (size_bytes >= 0),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE IF NOT EXISTS domain_study_runs (
    id UUID PRIMARY KEY,
    study_id UUID NOT NULL REFERENCES domain_studies(id) ON DELETE CASCADE,
    multiplanar_run_id TEXT NOT NULL UNIQUE,
    trace_id TEXT NOT NULL,
    requested_inference_mode TEXT NOT NULL,
    effective_inference_mode TEXT NOT NULL,
    sagittal_model_key TEXT NOT NULL,
    axial_model_key TEXT NOT NULL,
    sagittal_artifact_hash TEXT NOT NULL DEFAULT '',
    axial_artifact_hash TEXT NOT NULL DEFAULT '',
    sagittal_run_id TEXT NOT NULL,
    axial_run_id TEXT NOT NULL,
    assets JSONB NOT NULL DEFAULT '{}'::jsonb,
    metrics_snapshot JSONB NOT NULL DEFAULT '{}'::jsonb,
    status TEXT NOT NULL DEFAULT 'created',
    review_status TEXT NOT NULL DEFAULT 'pending' CHECK (review_status IN ('pending', 'accepted', 'rejected', 'edited')),
    reviewer TEXT NOT NULL DEFAULT '',
    reviewed_at TIMESTAMPTZ NULL,
    comments TEXT NOT NULL DEFAULT '',
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE IF NOT EXISTS domain_run_artifacts (
    id UUID PRIMARY KEY,
    study_run_id UUID NOT NULL REFERENCES domain_study_runs(id) ON DELETE CASCADE,
    run_id TEXT NOT NULL,
    plane TEXT NOT NULL CHECK (plane IN ('sagittal', 'axial')),
    asset_name TEXT NOT NULL,
    content_type TEXT NOT NULL,
    artifact_ref TEXT NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (study_run_id, plane, asset_name)
);

CREATE INDEX IF NOT EXISTS idx_domain_input_resources_study_plane ON domain_input_resources(study_id, plane);
CREATE INDEX IF NOT EXISTS idx_domain_study_runs_study ON domain_study_runs(study_id);
CREATE INDEX IF NOT EXISTS idx_domain_study_runs_trace_id ON domain_study_runs(trace_id);
CREATE INDEX IF NOT EXISTS idx_domain_run_artifacts_run_plane ON domain_run_artifacts(run_id, plane);
