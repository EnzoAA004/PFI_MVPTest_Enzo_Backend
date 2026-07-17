# Audit Event Model

BE-009 adds persisted audit events for key backend actions.

## Table

`domain_audit_events`

- `id`: UUID primary key.
- `actor`: professional/user/system actor; defaults to `system` when unknown.
- `action`: stable action name, for example `upload.input.completed`, `multiplanar.run.completed`, `review.updated`, `error.http`.
- `entity_id`: related input id, run id, review run id, or error entity.
- `trace_id`: trace id for correlation.
- `metadata`: safe JSON metadata only.
- `created_at`: event timestamp.

Indexes exist for `trace_id`, `entity_id`, and `action`.

## Instrumented Points

- Upload: `POST /api/ai/inputs` records `upload.input.completed` after the AI Module returns `inputId`.
- Multiplanar run: `POST /api/ai/multiplanar/run` records `multiplanar.run.completed` after the AI Module returns a valid response.
- Review: `POST/PUT /api/ai/runs/{multiplanarRunId}/review` records `review.updated` after the review is persisted.
- Errors: `ApiExceptionHandler` records `error.http` for handled API errors without masking the original error if audit persistence fails.
- Login/auth: `POST /api/auth/login` records `auth.login.completed` after a successful login flow without storing email, password, token, or refresh token values.

## Safe Metadata

Audit metadata is sanitized before persistence. Keys containing `token`, `secret`, `password`, `credential`, `authorization`, `path`, `filename`, `file`, `email`, or `patient` are dropped. Values that look like filesystem paths are redacted. No blobs, filesystem paths, secrets, tokens, or identifiable patient data should be stored.
