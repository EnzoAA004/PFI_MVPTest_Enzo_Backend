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
