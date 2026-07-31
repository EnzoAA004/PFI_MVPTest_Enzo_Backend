# P10.5-F Backend E2E Integration

Scope: FASE 2 integrates Backend with the AI Module study ZIP ingestion from AI commit `48d6dd8b02ec26eff3180b61a3d3583eca19b542`, without changing the AI Module or Frontend.

## Public Endpoint

`POST /api/ai/studies` accepts multipart form data:

- `file`: a `.zip` study bundle.
- `caseId`: pseudonymous case identifier.

The endpoint is authenticated like the rest of `/api/ai`: approved professionals and admins can use it; anonymous and `PENDING_APPROVAL` users are rejected by the auth filter.

The public response is intentionally typed and closed:

```json
{
  "caseId": "CASE-001",
  "studyId": "study-123",
  "seriesFound": [
    {
      "plane": "sagittal",
      "description": "Sag T2",
      "weighting": "T2",
      "sliceCount": 24
    }
  ],
  "sagittal": {
    "inputId": "input-sag",
    "plane": "sagittal",
    "format": "dcm",
    "size": 42,
    "description": "Sag T2",
    "weighting": "T2",
    "sliceCount": 24
  },
  "axial": null,
  "warnings": [],
  "humanReviewRequired": true,
  "notClinicalDiagnosis": true
}
```

No raw upstream fields are forwarded. The Backend deliberately omits local paths, storage roots, token-like fields, stack traces, hostnames, arbitrary metadata, and DICOM UIDs from the public response. `seriesInstanceUid` stays out of the contract because the current Frontend handoff only needs the selected public planes and opaque `inputId` values.

## Upload Policy

Default limit is 200 MiB (`209715200` bytes). This is stricter than the AI Module default and is aligned in `docker-compose.yml` by setting `PFI_MAX_UPLOAD_BYTES` for the AI container and `PFI_AI_STUDY_UPLOAD_MAX_BYTES` for the Backend.

Configuration:

- Backend: `PFI_AI_STUDY_UPLOAD_MAX_BYTES`, surfaced as `studyUploadMaxBytes` in `GET /api/ai/health`.
- Spring multipart: `PFI_MAX_UPLOAD_FILE_SIZE` and `PFI_MAX_UPLOAD_REQUEST_SIZE`, still defaulting to `200MB`.
- AI Module compose env: `PFI_MAX_UPLOAD_BYTES=${PFI_AI_STUDY_UPLOAD_MAX_BYTES:-209715200}`.

Accepted study MIME types are `application/zip`, `application/x-zip-compressed`, and `application/octet-stream`. A missing/blank content type is tolerated only after the `.zip` extension and ZIP magic signature pass.

## Error Mapping

Backend validation rejects malformed client inputs before calling AI:

- missing/empty file: `400 BAD_REQUEST`
- invalid `caseId`: `400 BAD_REQUEST`
- non-`.zip` filename, including null filename: `400 BAD_REQUEST`
- invalid ZIP signature: `400 BAD_REQUEST`
- size above `studyUploadMaxBytes`: `413 INPUT_TOO_LARGE`

AI Module rejections are mapped without copying upstream bodies:

- upstream `400`: `400`, safe Backend-authored message
- upstream `413`: `413`, safe Backend-authored message
- upstream `422`: `422`, safe Backend-authored message
- upstream transport/5xx/unknown status: `502 UPSTREAM_UNAVAILABLE`
- timeout: existing `504 AI_TIMEOUT` path from `AiServiceClient`

Every AI call carries `X-Trace-Id`; the Frontend authorization token is never forwarded to the AI Module. The Backend audit event stores safe metadata only: `caseId`, `studyId`, `traceId`, detected planes and series count.

## Opt-In Harness

The normal Maven test suite does not require a live AI Module. Manual end-to-end checks can be run only when explicitly enabled.

The harness validates the full Backend slice of P10.5-F:

- public liveness for Backend and AI Module
- approved-professional authentication, plus anonymous upload rejection
- `POST /api/ai/studies` with a real ZIP study bundle
- strict `real_baseline` `POST /api/ai/multiplanar/run` without contract fallback
- slice catalog shape, selected-slice indexes, backend-relative asset URLs, and PNG asset downloads
- professional correction through `POST /api/ai/runs/{runId}/review`
- first reopen through `GET /api/studies/{caseId}` and `GET /api/studies/{caseId}/runs`
- AI Module stop, Backend liveness with AI unavailable, optional Backend restart while Postgres stays up
- second reopen and durable asset download with AI still stopped
- AI Module restoration and sanitized JSON evidence

The script is fail-closed behind `RUN_PFI_E2E=1` and does not embed fixture bytes, tokens, DICOM, MHA, model outputs, or datasets.

Example with an already running topology:

```powershell
$env:RUN_PFI_E2E = "1"
$env:PFI_E2E_BACKEND_BASE_URL = "http://localhost:8080"
$env:PFI_E2E_AI_BASE_URL = "http://localhost:8000"
$env:PFI_E2E_AUTH_TOKEN = "<approved-professional-or-admin-jwt>"
$env:PFI_E2E_STUDY_ZIP = "C:\path\to\pseudonymized-study.zip"
.\scripts\run_p10_5_f_backend_e2e.ps1
```

Example with Docker Compose service control:

```powershell
$env:RUN_PFI_E2E = "1"
$env:PFI_E2E_BACKEND_BASE_URL = "http://localhost:8080"
$env:PFI_E2E_AI_BASE_URL = "http://localhost:8000"
$env:PFI_E2E_COMPOSE_PROJECT_DIR = (Resolve-Path -LiteralPath ".").Path
$env:PFI_E2E_COMPOSE_FILES = "docker-compose.yml;C:\tmp\p10_5_f_backend_e2e.override.yml"
$env:PFI_E2E_STUDY_ZIP = "C:\tmp\p10_5_f_real_dicom_fixture.zip"
$env:PFI_E2E_RESTART_BACKEND = "1"
.\scripts\run_p10_5_f_backend_e2e.ps1
```

Optional local-only knobs:

- `PFI_E2E_ALLOW_DEMO_AUTH=1`: obtains a local demo professional token from `/api/auth/demo-doctor`. This is for local evidence only and is recorded as a limitation.
- `PFI_E2E_GENERATE_SYNTHETIC_DICOM=1`: creates a temporary synthetic DICOM ZIP through `PFI_E2E_PYTHON`. This is useful for upload smoke checks, but it may not produce real model measurements and must not be used as proof of the full volumetric E2E.
- `PFI_E2E_MANAGE_SERVICES=1`: runs `docker compose up -d postgres ai-module backend` before validation.
- `PFI_E2E_AI_CONTAINER_NAME`: defaults to `pfi-ai-module` and is used to verify the AI container is truly stopped before durable-serving checks.

## Runtime Fixes Validated

- `AiServiceProperties` explicitly marks its canonical record constructor with `@ConstructorBinding`. This is required because the record also has a test convenience constructor; the Docker JAR previously failed startup with `No default constructor found`.
- `MultiplanarRealBaselineContractValidator` accepts missing `inPlaneSpacingUnit` when `inPlaneSpacing` is present and positive. The frozen AI v1 producer returns DICOM spacing values in millimeters but does not emit a separate unit field for this fixture. If the unit is present, it still must be `mm`.
- `docker-compose.yml` now exposes `PFI_AI_SERVICE_MULTIPLANAR_CONTRACT_VERSION`, defaulting to `v1`, matching the frozen AI response schema `multiplanar-run-v1`.

## Evidence 2026-07-30

Final successful run was executed locally on 2026-07-30 at 21:53:37 America/Buenos_Aires, using:

- Backend branch initial HEAD: `e9a632c7ab32870291b44105b07b150b0284d47a`
- AI Module frozen checkout: `48d6dd8b02ec26eff3180b61a3d3583eca19b542`, checked out in a temporary `C:\tmp` worktree
- Compose services: `postgres`, `ai-module`, `backend`
- Fixture source: external local ZIP, recorded only as `[path]\p10_5_f_real_dicom_fixture.zip`
- Auth limitation: local demo-doctor token was used because no real professional JWT was provided in the shell

Sanitized summary highlights:

```json
{
  "caseId": "P10-5-F-E2E-20260730215337",
  "upload": {"status": 200, "seriesCount": 513, "sagittalInputIdPresent": true, "axialInputIdPresent": false},
  "multiplanar": {"status": 200, "runId": "multi-70305a1107ebac3915e4", "planes": [{"plane": "sagittal", "sliceCount": 2}]},
  "review": {"status": 200, "correctionFoundOnReopen": true},
  "aiStopped": {"aiUnavailable": true, "backend": "up"},
  "backendRestart": {"postgresKept": true},
  "secondReopen": {"studyStatus": 200, "runsStatus": 200, "durableAssetShaMatched": true}
}
```

Assets verified as Backend-served PNGs:

- `before_ai_stop.selected_preview`: `slice-000.png`, 35275 bytes, `sha256_16=3b2ab6ef8660797d`
- `before_ai_stop.overlay`: `slice-000-overlay.png`, 64747 bytes, `sha256_16=8b720fc646f50c66`
- `after_ai_stop.selected_preview`: `slice-000.png`, same SHA
- `after_ai_stop.overlay`: `slice-000-overlay.png`, same SHA

Backend log scan found six sanitized Backend `/api/ai/...` lines for the run and asset downloads. No tokens or fixture paths were copied into the committed evidence.
