# Local Backend and AI Module Integration

Este backend Spring Boot consume el AI Module FastAPI por HTTP. No ejecuta modelos en Java y no emite diagnostico clinico; toda salida tecnica queda marcada con `humanReviewRequired=true` y `notClinicalDiagnosis=true`.

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
```

En Bash:

```bash
export PFI_AI_SERVICE_URL=http://localhost:8000
export PFI_AI_TIMEOUT_SECONDS=60
export PFI_CORS_ALLOWED_ORIGINS=http://localhost:5173,http://localhost:3000
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

## Errores Comunes

- `aiModuleAvailable=false`: el AI Module no esta levantado o `PFI_AI_SERVICE_URL` apunta a otro host.
- `502 BAD_GATEWAY`: el backend no pudo conectar con FastAPI o FastAPI devolvio error.
- `400 BAD_REQUEST` en review: el estado debe ser `pendiente`, `aceptado`, `observado` o `descartado`.
- Error CORS desde React: agregar el origen del frontend a `PFI_CORS_ALLOWED_ORIGINS`.
