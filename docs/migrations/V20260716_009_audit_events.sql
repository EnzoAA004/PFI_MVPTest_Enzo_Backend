-- BE-009: persisted audit events for key backend actions.
-- Metadata must remain safe: no tokens, credentials, filesystem paths, PII, or blobs.

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
