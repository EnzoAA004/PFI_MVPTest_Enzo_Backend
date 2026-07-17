-- PFI Backend persistence schema draft.
-- Academic/de-identified data only. No direct patient identifiers.

CREATE TABLE IF NOT EXISTS doctor_accounts (
    id TEXT PRIMARY KEY,
    full_name TEXT NOT NULL,
    email TEXT NOT NULL UNIQUE,
    password_hash TEXT NOT NULL,
    license_number TEXT NOT NULL DEFAULT '',
    specialty TEXT NOT NULL DEFAULT '',
    institution TEXT NOT NULL DEFAULT '',
    roles TEXT NOT NULL DEFAULT 'PENDING_APPROVAL',
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    verified BOOLEAN NOT NULL DEFAULT FALSE,
    approved BOOLEAN NOT NULL DEFAULT FALSE,
    two_factor_enabled BOOLEAN NOT NULL DEFAULT FALSE,
    onboarding_completed BOOLEAN NOT NULL DEFAULT FALSE
);

CREATE TABLE IF NOT EXISTS auth_refresh_tokens (
    token_hash TEXT PRIMARY KEY,
    email TEXT NOT NULL REFERENCES doctor_accounts(email) ON DELETE CASCADE,
    issued_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    expires_at TIMESTAMPTZ NOT NULL,
    revoked_at TIMESTAMPTZ NULL
);

CREATE TABLE IF NOT EXISTS studies (
    case_id TEXT PRIMARY KEY,
    subject_ref TEXT NOT NULL,
    plane TEXT NOT NULL,
    study_date TEXT NOT NULL,
    review_status TEXT NOT NULL DEFAULT 'pendiente',
    priority TEXT NOT NULL DEFAULT 'media',
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE IF NOT EXISTS study_runs (
    run_id TEXT PRIMARY KEY,
    case_id TEXT NOT NULL REFERENCES studies(case_id) ON DELETE CASCADE,
    plane TEXT NOT NULL,
    model_key TEXT NOT NULL,
    model_status TEXT NOT NULL,
    primary_run BOOLEAN NOT NULL DEFAULT FALSE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE IF NOT EXISTS review_statuses (
    run_id TEXT PRIMARY KEY,
    status TEXT NOT NULL,
    notes TEXT NOT NULL DEFAULT '',
    reviewer TEXT NOT NULL DEFAULT '',
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE IF NOT EXISTS measurement_reviews (
    run_id TEXT NOT NULL,
    measurement_id TEXT NOT NULL,
    label TEXT NOT NULL,
    payload JSONB NOT NULL DEFAULT '{}'::jsonb,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (run_id, measurement_id)
);

CREATE TABLE IF NOT EXISTS audit_events (
    id TEXT PRIMARY KEY,
    reviewer TEXT NOT NULL DEFAULT 'System',
    action TEXT NOT NULL,
    detail TEXT NOT NULL DEFAULT '',
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX IF NOT EXISTS idx_auth_refresh_tokens_email ON auth_refresh_tokens(email);
CREATE INDEX IF NOT EXISTS idx_studies_subject_ref ON studies(subject_ref);
CREATE INDEX IF NOT EXISTS idx_study_runs_case_id ON study_runs(case_id);
CREATE INDEX IF NOT EXISTS idx_measurement_reviews_run_id ON measurement_reviews(run_id);
CREATE INDEX IF NOT EXISTS idx_audit_events_created_at ON audit_events(created_at DESC);

-- BE-005 domain model for de-identified studies, AI Module inputs, and multiplanar runs.
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
    review_status TEXT NOT NULL DEFAULT 'pending' CHECK (review_status IN ('pending', 'accepted', 'observed', 'rejected', 'edited')),
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

CREATE INDEX IF NOT EXISTS idx_domain_input_resources_study_plane ON domain_input_resources(study_id, plane);
CREATE INDEX IF NOT EXISTS idx_domain_study_runs_study ON domain_study_runs(study_id);
CREATE INDEX IF NOT EXISTS idx_domain_study_runs_trace_id ON domain_study_runs(trace_id);
CREATE INDEX IF NOT EXISTS idx_domain_run_artifacts_run_plane ON domain_run_artifacts(run_id, plane);
CREATE INDEX IF NOT EXISTS idx_domain_review_corrections_run ON domain_review_corrections(study_run_id);

CREATE TABLE IF NOT EXISTS domain_audit_events (
    id UUID PRIMARY KEY,
    actor TEXT NOT NULL DEFAULT 'system',
    action TEXT NOT NULL,
    entity_id TEXT NOT NULL DEFAULT '',
    trace_id TEXT NOT NULL DEFAULT '',
    metadata JSONB NOT NULL DEFAULT '{}'::jsonb,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX IF NOT EXISTS idx_domain_audit_events_trace_id ON domain_audit_events(trace_id);
CREATE INDEX IF NOT EXISTS idx_domain_audit_events_entity_id ON domain_audit_events(entity_id);
CREATE INDEX IF NOT EXISTS idx_domain_audit_events_action ON domain_audit_events(action);
