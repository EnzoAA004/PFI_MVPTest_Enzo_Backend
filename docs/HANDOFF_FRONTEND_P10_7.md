# Handoff Frontend P10.7 Disc Degenerative Findings

## Base SHAs

```text
AI Module main inspected: b0886b14ac8bac03e0bbaa94beeca5dbf8b40c45
AI Module P10.7 evidence branch inspected: f5b938036af4a7858e712465a7b27018ca76dc02
Backend main inspected: 4244950f1703c0f0b7d64bce6fc934e675a1e241
Frontend modified: false
```

## Architecture

```text
Frontend React -> Backend Spring Boot -> AI Module FastAPI
```

Frontend never calls FastAPI directly.

## Public Backend Endpoint

```http
POST /api/ai/degenerative-findings/disc-multitask/predict
```

Request:

```json
{
  "multiplanarRunId": "opaque-run-id",
  "levels": ["L4-L5"]
}
```

The endpoint currently fails closed with `422` unless the persisted backend
snapshot includes validated P10.7 runtime inputs.

## Contract

```text
schemaVersion: pfi.disc-degenerative-findings.v1
root: discDegenerativeFindings
```

P10.6 remains:

```text
schemaVersion: pfi.degenerative-findings.v1
root: degenerativeFindings
```

Do not merge the two roots or reuse P10.6 severity enums for P10.7.

## Finding Types and Labels

```text
disc_bulging -> Abombamiento discal
disc_narrowing -> Estrechamiento discal
upper_endplate_change -> Cambio del platillo superior
lower_endplate_change -> Cambio del platillo inferior
disc_herniation -> Hernia discal - experimental/no respaldada para presentacion principal
spondylolisthesis -> Espondilolistesis - experimental/no respaldada para presentacion principal
pfirrmann_grade -> Grado de Pfirrmann - experimental
modic_change -> Cambio Modic - experimental/no respaldado para presentacion principal
```

Labels:

```text
pfirrmann_grade: I, II, III, IV, V
modic_change: none, I, II, III
binary tasks: absent, present
```

Deployment status:

```text
supported_internal
experimental
not_product_supported
```

Review statuses:

```text
pending
accepted
observed
rejected
edited
```

New predictions always start as:

```json
{"required": true, "status": "pending"}
```

## Example Response

```json
{
  "discDegenerativeFindings": {
    "schemaVersion": "pfi.disc-degenerative-findings.v1",
    "findings": [
      {
        "findingId": "opaque-id",
        "findingType": "disc_bulging",
        "anatomy": {"level": "L4-L5", "side": null},
        "classification": {
          "kind": "binary",
          "label": "present",
          "probabilities": {"absent": 0.12, "present": 0.88}
        },
        "evidence": {
          "deploymentStatus": "supported_internal",
          "evaluationDataset": "SPIDER_internal_test",
          "externalValidationAvailable": false
        },
        "evaluation": {"status": "evaluated"},
        "sourceSeries": [
          {"role": "sagittal_t1", "available": true, "positions": [10, 11, 12]},
          {"role": "sagittal_t2", "available": true, "positions": [9, 10, 11]}
        ],
        "localization": {
          "source": "segmentation_derived_disc_level",
          "researchOnly": true,
          "automaticAnatomicalLocalizationValidated": false
        },
        "model": {
          "modelId": "spider_degenerative_multitask_sagittal_t1_t2_2p5d",
          "modelSha256": "16eccff327e6794b127fe372ecd03ea619a0f69d939b84ae1aa2e904191c6293"
        },
        "review": {"required": true, "status": "pending"},
        "notClinicalDiagnosis": true
      }
    ]
  },
  "humanReviewRequired": true,
  "notClinicalDiagnosis": true,
  "autonomousDiagnosis": false
}
```

## Readiness

Backend obtains model status through AI Module readiness/model endpoints. P10.7
status appears as `degenerativeFindingModels.discMultitask`.

Important flags:

```text
preprocessingParityValidated: false
automaticDiscLocalizationValidated: false
```

When either is false, UI should treat P10.7 as unavailable for productive
analysis.

## Errors

```text
404: run not found
422: level, localization, or preprocessing unavailable
503: checkpoint unavailable/hash invalid/upstream unavailable
500: sanitized internal error
```

Suggested UI messages:

```text
422: P10.7 todavia no tiene localizacion/preprocesamiento validado para esta corrida.
503: El modelo P10.7 no esta disponible temporalmente.
500: No se pudo completar el analisis.
```

## Methodological Limits

Do not say:

```text
lesion detectada
diagnostico automatico
resultado definitivo
tratamiento recomendado
```

Use:

```text
hallazgo degenerativo candidato
clasificacion asistida
resultado para revision profesional
probabilidad estimada por el modelo
```

## Gates

```text
FRONTEND_MODIFIED = false
FULL_PRODUCT_E2E_VALIDATED = false
BACKEND_AI_P10_7_E2E_VALIDATED = false
```

The total product E2E can only close after:

```text
Backend real -> AI Module real -> checkpoint frozen real -> contract validation
-> persistence -> rehydration -> Frontend render/review
```
