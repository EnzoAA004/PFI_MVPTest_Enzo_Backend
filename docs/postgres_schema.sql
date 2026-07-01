-- PFI Backend persistence schema draft.
-- Academic/de-identified data only. No direct patient identifiers.

CREATE TABLE IF NOT EXISTS doctors (
    id UUID PRIMARY KEY,
    full_name TEXT NOT NULL,
    email TEXT NOT NULL UNIQUE,
    password_hash TEXT NOT NULL,
    license_number TEXT,
    specialty TEXT,
    institution TEXT,
    roles TEXT[] NOT NULL DEFAULT ARRAY['DOCTOR','REVIEWER'],
    verified BOOLEAN NOT NULL DEFAULT FALSE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE IF NOT EXISTS patients_deidentified (
    id TEXT PRIMARY KEY,
    synthetic_label TEXT NOT NULL,
    metadata JSONB NOT NULL DEFAULT '{}'::jsonb,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE IF NOT EXISTS studies (
    id TEXT PRIMARY KEY,
    case_id TEXT NOT NULL UNIQUE,
    patient_id TEXT NOT NULL REFERENCES patients_deidentified(id),
    study_date DATE,
    modality TEXT NOT NULL DEFAULT 'MRI',
    body_region TEXT NOT NULL DEFAULT 'Lumbar Spine',
    model_key TEXT,
    model_version TEXT,
    ai_output_status TEXT NOT NULL DEFAULT 'ai_output_pending',
    metadata JSONB NOT NULL DEFAULT '{}'::jsonb,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE IF NOT EXISTS pipeline_runs (
    run_id TEXT PRIMARY KEY,
    case_id TEXT NOT NULL REFERENCES studies(case_id),
    plane TEXT NOT NULL,
    model_key TEXT NOT NULL,
    input_path TEXT,
    ai_module_available BOOLEAN NOT NULL DEFAULT TRUE,
    degraded_mode BOOLEAN NOT NULL DEFAULT FALSE,
    response_payload JSONB NOT NULL DEFAULT '{}'::jsonb,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE IF NOT EXISTS review_statuses (
    run_id TEXT PRIMARY KEY REFERENCES pipeline_runs(run_id),
    status TEXT NOT NULL CHECK (status IN ('pendiente','aceptado','observado','descartado')),
    notes TEXT NOT NULL DEFAULT '',
    reviewer TEXT NOT NULL DEFAULT '',
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE IF NOT EXISTS measurement_reviews (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    run_id TEXT NOT NULL REFERENCES pipeline_runs(run_id),
    measurement_id TEXT NOT NULL,
    label TEXT NOT NULL,
    ai_value JSONB,
    reviewer_value JSONB,
    unit TEXT,
    confidence NUMERIC,
    plane TEXT,
    source TEXT NOT NULL DEFAULT 'Reviewer',
    status TEXT NOT NULL DEFAULT 'editado',
    outlier BOOLEAN NOT NULL DEFAULT FALSE,
    payload JSONB NOT NULL DEFAULT '{}'::jsonb,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (run_id, measurement_id)
);

CREATE TABLE IF NOT EXISTS audit_events (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    run_id TEXT,
    reviewer TEXT NOT NULL DEFAULT 'System',
    action TEXT NOT NULL,
    detail TEXT NOT NULL DEFAULT '',
    payload JSONB NOT NULL DEFAULT '{}'::jsonb,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX IF NOT EXISTS idx_studies_patient_id ON studies(patient_id);
CREATE INDEX IF NOT EXISTS idx_pipeline_runs_case_id ON pipeline_runs(case_id);
CREATE INDEX IF NOT EXISTS idx_measurement_reviews_run_id ON measurement_reviews(run_id);
CREATE INDEX IF NOT EXISTS idx_audit_events_run_id ON audit_events(run_id);
CREATE INDEX IF NOT EXISTS idx_audit_events_created_at ON audit_events(created_at DESC);
