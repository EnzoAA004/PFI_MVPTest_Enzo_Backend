# P10.7 Disc Degenerative Backend Contract

## Architecture

```text
Frontend React -> Backend Spring Boot -> AI Module FastAPI
```

Frontend must not call the AI Module directly.

## Public Endpoint

```http
POST /api/ai/degenerative-findings/disc-multitask/predict
Content-Type: application/json
```

Request:

```json
{
  "multiplanarRunId": "opaque-run-id",
  "levels": ["L4-L5", "L5-S1"]
}
```

If `levels` is absent or empty, the backend defaults to all five lumbar disc
levels.

## AI Module Endpoint

```http
POST /degenerative-findings/disc-multitask/predict
```

The backend calls this endpoint only when the persisted run snapshot contains a
validated `discDegenerativeRuntimeInputs` block:

```json
{
  "preprocessingParityValidated": true,
  "automaticDiscLocalizationValidated": true,
  "sourceSeries": [
    {"role": "sagittal_t1", "inputId": "inp_t1", "available": true, "positions": [10, 11, 12]},
    {"role": "sagittal_t2", "inputId": "inp_t2", "available": true, "positions": [9, 10, 11]}
  ],
  "localization": {
    "source": "segmentation_derived_disc_level",
    "researchOnly": true,
    "automaticAnatomicalLocalizationValidated": false
  }
}
```

Current multiplanar snapshots do not prove this yet, so the endpoint returns
`422` instead of inventing parity.

## Response Contract

Root field:

```text
discDegenerativeFindings
```

Schema:

```text
pfi.disc-degenerative-findings.v1
```

The P10.6 field remains unchanged:

```text
degenerativeFindings
pfi.degenerative-findings.v1
```

## Finding Types

```text
pfirrmann_grade
modic_change
upper_endplate_change
lower_endplate_change
spondylolisthesis
disc_herniation
disc_narrowing
disc_bulging
```

Labels:

```text
pfirrmann_grade: I, II, III, IV, V
modic_change: none, I, II, III
binary tasks: absent, present
```

Deployment status:

```text
supported_internal:
- upper_endplate_change
- lower_endplate_change
- disc_narrowing
- disc_bulging

experimental:
- pfirrmann_grade

not_product_supported:
- modic_change
- spondylolisthesis
- disc_herniation
```

## Persistence

On a valid AI response the backend stores:

```text
metricsSnapshot.discDegenerativeFindings
metricsSnapshot.discDegenerativeFindingsOriginal
```

No SQL migration is required. Rehydration preserves `discDegenerativeFindings`
in the canonical run detail. `degenerativeFindings` P10.6 is not renamed or
modified.

## Errors

```text
404: run not found
422: invalid level, missing runtime input parity, missing localization
503: AI checkpoint unavailable or upstream unavailable
500: sanitized internal error
```

The backend never exposes paths, stack traces, checkpoint paths, DICOM UIDs,
PatientID, or PatientName.

## Current Gates

```text
BACKEND_P10_7_CONTRACT_IMPLEMENTED = true
BACKEND_P10_7_PERSISTENCE_VALIDATED = partial
BACKEND_AI_P10_7_E2E_VALIDATED = false
AI_P10_7_PREPROCESSING_PARITY_VALIDATED = false
AI_P10_7_AUTOMATIC_LOCALIZATION_VALIDATED = false
```

The bridge is intentionally fail-closed until AI Module and Backend can prove
that runtime T1/T2 crops and disc localization match Notebook 66.
