# Local Backend and AI Module Integration

Este backend Spring Boot consume el AI Module FastAPI por HTTP. No ejecuta modelos en Java y no emite diagnostico clinico; toda salida tecnica queda marcada con `humanReviewRequired=true` y `notClinicalDiagnosis=true`.

La separacion de repos es obligatoria: el frontend llama al backend, y el backend llama al AI Module por HTTP usando `PFI_AI_SERVICE_URL`. Este repositorio no importa codigo Python, no abre rutas internas de `models/final`, no descarga checkpoints y no accede a GCS.

## 1. Levantar AI Module

Desde el repositorio del modulo Python:

```bash
uvicorn app.main:app --host 0.0.0.0 --port 8000
```

Verificar:

```bash
curl http://localhost:8000/health
curl http://localhost:8000/models
```

## 2. Configurar Backend

Por defecto el backend apunta a `http://localhost:8000`.

```bash
set PFI_AI_SERVICE_URL=http://localhost:8000
set PFI_AI_TIMEOUT_SECONDS=60
set PFI_CORS_ALLOWED_ORIGINS=http://localhost:5173,http://localhost:3000
set PFI_SAGITTAL_EXPECTED_MODEL_KEY=sagittal_spider
set PFI_SAGITTAL_EXPECTED_MODEL_VERSION=sagittal-spider-final-v1
set PFI_SAGITTAL_EXPECTED_MODEL_SHA256=cf11dcc0ad77a7c787e64a796a2fd7398ef906add461cef4b3d61f1a5238e944
```

En Bash:

```bash
export PFI_AI_SERVICE_URL=http://localhost:8000
export PFI_AI_TIMEOUT_SECONDS=60
export PFI_CORS_ALLOWED_ORIGINS=http://localhost:5173,http://localhost:3000
export PFI_SAGITTAL_EXPECTED_MODEL_KEY=sagittal_spider
export PFI_SAGITTAL_EXPECTED_MODEL_VERSION=sagittal-spider-final-v1
export PFI_SAGITTAL_EXPECTED_MODEL_SHA256=cf11dcc0ad77a7c787e64a796a2fd7398ef906add461cef4b3d61f1a5238e944
```

## 3. Levantar Backend

```bash
mvn spring-boot:run
```

El backend queda disponible en `http://localhost:8080`.

## 4. Probar con curl

```bash
curl http://localhost:8080/api/ai/health
curl http://localhost:8080/api/ai/models
```

```bash
curl -X POST http://localhost:8080/api/ai/pipeline/run \
  -H "Content-Type: application/json" \
  -d '{"caseId":"case-001","plane":"sagittal","modelKey":"baseline","inputPath":"studies/case-001"}'
```

```bash
curl -X PATCH http://localhost:8080/api/ai/review/run-001 \
  -H "Content-Type: application/json" \
  -d '{"status":"observado","notes":"Revision local","reviewer":"dr-demo"}'
```

Tambien se puede usar:

```bash
bash scripts/smoke_backend.sh
```

## 5. Sagittal real_baseline estricto

`POST /api/ai/pipeline/run` entra en modo estricto cuando `metadata.inferenceMode=real_baseline` y `metadata.allowContractFallback=false`. Si `allowContractFallback` falta, el backend agrega `false`.

En modo estricto el backend exige `plane=sagittal`, normaliza `modelKey=sagittal_spider`, requiere `inputPath`, valida `modelVersion` y `artifactHash`, no genera fallback demo, y no devuelve `pipeline_degraded_fallback` como exito. Las rutas internas del AI Module se eliminan y los assets publicos se reescriben a `/api/ai/assets/{runId}/{plane}/{assetName}`.

El E2E real es opt-in:

```powershell
$env:RUN_BACKEND_REAL_E2E="1"
$env:PFI_E2E_INPUT_PATH="C:\ruta\fixture.mha"
$env:PFI_BACKEND_BEARER_TOKEN="<token>"
.\scripts\run_sagittal_real_backend_e2e.ps1
```

El script llama solo al backend, no al AI Module directo, y no imprime headers de autorizacion.

## Errores Comunes

- `aiModuleAvailable=false`: el AI Module no esta levantado o `PFI_AI_SERVICE_URL` apunta a otro host.
- `502 BAD_GATEWAY`: el backend no pudo conectar con FastAPI o FastAPI devolvio error.
- `400 BAD_REQUEST` en review: el estado debe ser `pendiente`, `aceptado`, `observado` o `descartado`.
- Error CORS desde React: agregar el origen del frontend a `PFI_CORS_ALLOWED_ORIGINS`.
