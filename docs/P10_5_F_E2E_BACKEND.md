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

The normal Maven test suite does not require a live AI Module. Manual end-to-end checks can be run only when explicitly enabled:

```powershell
$env:RUN_PFI_E2E = "1"
$env:PFI_E2E_BACKEND_BASE_URL = "http://localhost:8080"
$env:PFI_E2E_AUTH_TOKEN = "<approved-professional-or-admin-jwt>"
$env:PFI_E2E_STUDY_ZIP = "C:\path\to\pseudonymized-study.zip"
.\scripts\run_p10_5_f_backend_e2e.ps1
```

The harness never embeds fixture data and does not commit ZIP, DICOM, MHA, model, output, dataset, or token files.
