# Study/Input/Run Data Model

BE-005 introduces a domain model for de-identified lumbar MRI studies and AI Module runs.

## Persistence Stack

The backend currently does not use Spring Data JPA, Flyway, Liquibase, or H2. Persistence in the existing codebase is implemented with PostgreSQL JDBC services and SQL documented in `docs/postgres_schema.sql`.

For BE-005b, the migration is captured as `docs/migrations/V20260716_005_study_input_run_model.sql` and mirrored in `docs/postgres_schema.sql`. `SqlMigrationRunner` applies versioned SQL files and records them in `schema_migrations`. `PostgresStudyRepository` uses JDBC and applies the migration at initialization when PostgreSQL persistence is enabled.

Testcontainers PostgreSQL is the acceptance gate for the real persistence path. The in-memory repository remains the default fast local fallback when `pfi.persistence.mode` is not `postgres`.

## Entities

`Study`

- `id`: internal UUID/string identifier.
- `caseId`: de-identified case identifier.
- `status`: workflow state.
- `createdAt`, `updatedAt`: timestamps.

`InputResource`

- `id`: internal UUID/string identifier.
- `studyId`: parent study.
- `plane`: `sagittal` or `axial`.
- `inputId`: AI Module input registry id.
- `format`, `size`: AI Module input metadata.
- `createdAt`: timestamp.

`StudyRun`

- `id`: internal UUID/string identifier.
- `studyId`: parent study.
- `multiplanarRunId`: common AI Module multiplanar run id.
- `traceId`: trace id returned by the AI Module/backend path.
- `requestedInferenceMode`, `effectiveInferenceMode`: requested and resolved inference modes.
- `sagittalModelKey`, `axialModelKey`: model keys by plane.
- `sagittalArtifactHash`, `axialArtifactHash`: checkpoint/artifact hash used by plane.
- `sagittalRunId`, `axialRunId`: child run ids by plane.
- `assets`: metadata/asset refs only; no image, mask, or model blobs.
- `metricsSnapshot`: JSON quality/confidence snapshot captured for the run.
- `artifacts`: generated output refs by `runId`, `plane`, and `assetName`; no blobs.
- `reviewStatus`, `reviewer`, `reviewedAt`, `comments`: professional review state columns, without introducing review workflow in this ticket.
- `status`, `createdAt`, `updatedAt`: workflow state and timestamps.

## ERD Notes For DOC-002

`Study` has many `InputResource` records and many `StudyRun` records. `StudyRun` has many generated `RunArtifact` refs. `StudyRun.traceId` and `StudyRun.multiplanarRunId` are indexed for traceability. Heavy artifacts remain outside the database.

## Migration Strategy

- Versioned SQL lives under `docs/migrations/`.
- `SqlMigrationRunner` applies pending files in filename order.
- Applied files are tracked in `schema_migrations(version, applied_at)`.
- Tests use Testcontainers PostgreSQL and assert that the BE-005b migration is applied before persistence checks.
- No binary image, mask, checkpoint, or clinical output blobs are stored in database tables.
