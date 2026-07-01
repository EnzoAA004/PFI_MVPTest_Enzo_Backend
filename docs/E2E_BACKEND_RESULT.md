# E2E Backend Result

Fecha: 2026-07-01

## mvn test

Resultado: `BUILD SUCCESS`

Resumen:

```text
Tests run: 5, Failures: 0, Errors: 0, Skipped: 0
```

## Servicios Locales

AI Module:

```text
http://localhost:8000
uvicorn pfi_ai_service.api:app
```

Backend:

```text
http://localhost:8080
PFI_AI_SERVICE_URL=http://localhost:8000 mvn spring-boot:run
```

## GET /api/ai/health

Resultado: `200 OK`

```json
{
  "status": "ok",
  "pfiRoot": "\\content\\drive\\MyDrive\\PFI_MVP",
  "humanReviewRequired": true,
  "notClinicalDiagnosis": true,
  "backendStatus": "up",
  "aiModuleAvailable": true
}
```

## GET /api/ai/models

Resultado: `200 OK`

Campos verificados:

```json
{
  "models": {
    "sagittalSpider": {
      "plane": "sagittal",
      "numClasses": 4,
      "humanReviewRequired": true
    },
    "axialT2Alkafri": {
      "plane": "axial",
      "numClasses": 6,
      "humanReviewRequired": true
    }
  },
  "paths": {
    "sagittalModelPath": "\\content\\drive\\MyDrive\\PFI_MVP\\models\\E12_sagittal_multiclass_final_best.pt",
    "axialModelPath": "\\content\\drive\\MyDrive\\PFI_MVP\\models\\E10_axial_t2_final_training_clean_best.pt"
  }
}
```

## POST /api/ai/pipeline/run

Request:

```json
{
  "caseId": "case-demo-001",
  "plane": "sagittal",
  "modelKey": "sagittal_spider",
  "inputPath": "demo/case-demo-001",
  "metadata": {
    "source": "backend-e2e"
  }
}
```

Resultado: `200 OK`

Campos principales:

```json
{
  "runId": "a63014c107adef94",
  "caseId": "case-demo-001",
  "plane": "sagittal",
  "modelKey": "sagittal_spider",
  "overlayPath": null,
  "agentDecision": {
    "agentStatus": "requires_professional_review",
    "humanReviewRequired": true,
    "notClinicalDiagnosis": true
  },
  "humanReviewRequired": true,
  "notClinicalDiagnosis": true,
  "review": {
    "status": "pendiente"
  }
}
```

## PATCH /api/ai/review/{runId}

Run ID usado: `a63014c107adef94`

Request:

```json
{
  "status": "observado",
  "notes": "Prueba E2E local",
  "reviewer": "demo"
}
```

Resultado: `200 OK`

```json
{
  "runId": "a63014c107adef94",
  "status": "observado",
  "notes": "Prueba E2E local",
  "reviewer": "demo"
}
```

## Normalizacion Verificada

El AI Module responde snake_case y el backend expone camelCase al frontend:

- `pfi_root` -> `pfiRoot`
- `human_review_required` -> `humanReviewRequired`
- `sagittal_spider` -> `sagittalSpider`
- `num_classes` -> `numClasses`
- `run_id` -> `runId`
- `case_id` -> `caseId`
- `model_key` -> `modelKey`
- `overlay_path` -> `overlayPath`
- `agent_decision` -> `agentDecision`
- `not_clinical_diagnosis` -> `notClinicalDiagnosis`

## Problemas Detectados

- `mvn clean test` no se uso como validacion final porque Windows bloqueo el borrado de un subdirectorio en `target` durante una corrida previa. `mvn test` compila y ejecuta correctamente.
- La inferencia del AI Module responde con `pending_real_inference`, que es esperable para esta prueba de contrato local.

## Estado Final

`ready`

El backend consume el AI Module por HTTP, no ejecuta IA en Java, preserva `humanReviewRequired=true`, conserva `notClinicalDiagnosis=true` cuando corresponde y permite revision profesional local con estados controlados.
