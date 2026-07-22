# PFI Backend

Backend Spring Boot independiente para el PFI. Este servicio expone una API REST para el frontend React, consume el AI Module FastAPI por HTTP y conserva la revision profesional local del resultado.

```text
Frontend React -> Spring Boot Backend -> Python FastAPI AI Module
```

El backend no ejecuta modelos de IA, no emite diagnostico clinico y no transforma la salida del AI Module en una decision medica. Toda respuesta de pipeline o reporte preserva `humanReviewRequired=true`: el profesional revisa, acepta, observa o descarta.

## Variables

Copiar `.env.example` como referencia:

```bash
PFI_AI_SERVICE_URL=http://localhost:8000
PFI_AI_TIMEOUT_SECONDS=60
PFI_CORS_ALLOWED_ORIGINS=http://localhost:5173,http://localhost:3000
PFI_AUTH_ENABLED=true
PFI_AUTH_JWT_SECRET=change-this-secret
PFI_AUTH_ACCESS_TOKEN_SECONDS=3600
PFI_AUTH_EXPOSE_DEV_CODES=true
PFI_SAGITTAL_EXPECTED_MODEL_KEY=sagittal_spider
PFI_SAGITTAL_EXPECTED_MODEL_VERSION=sagittal-spider-final-v1
PFI_SAGITTAL_EXPECTED_MODEL_SHA256=cf11dcc0ad77a7c787e64a796a2fd7398ef906add461cef4b3d61f1a5238e944
PORT=8080
```

## Comandos Locales

```bash
mvn test
mvn spring-boot:run
```

Con Docker:

```bash
docker build -t pfi-backend .
docker run --rm -p 8080:8080 --env PFI_AI_SERVICE_URL=http://host.docker.internal:8000 pfi-backend
```

## Endpoints principales

- `GET /api/ai/health`: consulta el health del AI Module.
- `GET /api/ai/models`: lista modelos disponibles en el AI Module.
- `POST /api/ai/inputs`: sube un archivo al AI Module y devuelve un `inputId` opaco, sin paths internos.
- `POST /api/ai/pipeline/run`: ejecuta el pipeline remoto y marca revision humana requerida.
- `POST /api/ai/models/sync`: endpoint administrativo que valida el release sagital real antes de marcarlo listo.
- `GET /api/ai/assets/{runId}/{plane}/{assetName}`: proxy unico para assets publicos del AI Module.
- `GET /api/ai/agent/report/{runId}`: obtiene el reporte del agente y agrega revision local.
- `GET /api/ai/studies/demo-review`: obtiene el contrato visual de estudio, series, mascaras, landmarks y mediciones.
- `PATCH /api/ai/review/{runId}`: actualiza la revision profesional local.
- `GET /api/ai/review/history`: devuelve snapshot de revisiones, mediciones editadas y auditoria.
- `GET /api/ai/review/{runId}/measurements`: devuelve mediciones editadas para una corrida.
- `PUT /api/ai/review/{runId}/measurements`: guarda mediciones editadas por el reviewer.
- `POST /api/ai/audit`: agrega evento de auditoria.
- `GET /api/ai/audit`: lista eventos recientes de auditoria.

## Auth academico

El MVP incluye login/register de doctor con doble verificacion demo, password hasheado, access token JWT y refresh token en memoria. Para demo se puede usar `POST /api/auth/demo-doctor`.

En produccion academica se recomienda configurar `PFI_AUTH_JWT_SECRET` con una clave privada larga y cambiar `PFI_AUTH_EXPOSE_DEV_CODES=false` cuando exista envio real por email.

## Ejemplos curl

```bash
curl http://localhost:8080/api/ai/health
curl http://localhost:8080/api/ai/models
```

```bash
curl -X POST http://localhost:8080/api/ai/inputs \
  -H "Authorization: Bearer <token>" \
  -F "file=@fixture.mha" \
  -F "caseId=case-001" \
  -F "plane=sagittal"
```

```bash
curl -X POST http://localhost:8080/api/ai/pipeline/run \
  -H "Content-Type: application/json" \
  -H "Authorization: Bearer <token>" \
  -d '{"caseId":"case-001","plane":"sagittal","modelKey":"sagittal_spider","inputId":"inp_case_001_sagittal","metadata":{"inferenceMode":"real_baseline","allowContractFallback":false}}'
```

```bash
curl http://localhost:8080/api/ai/agent/report/run-001 \
  -H "Authorization: Bearer <token>"
```

```bash
curl -X PATCH http://localhost:8080/api/ai/review/run-001 \
  -H "Content-Type: application/json" \
  -H "Authorization: Bearer <token>" \
  -d '{"status":"observado","notes":"Revisar medicion L4-L5","reviewer":"dr-demo"}'
```

```bash
curl -X PUT http://localhost:8080/api/ai/review/run-001/measurements \
  -H "Content-Type: application/json" \
  -H "Authorization: Bearer <token>" \
  -d '{"reviewer":"dr-demo","detail":"Ajuste de medicion","measurements":[]}'
```

## Roadmap de persistencia

La persistencia activa del MVP sigue siendo in-memory para no bloquear despliegues en Render Free y validar el flujo funcional. El esquema SQL preparado para Postgres esta en:

```text
docs/postgres_schema.sql
```

Ese esquema contempla:

- doctores;
- pacientes de-identificados;
- estudios;
- corridas de pipeline;
- estados de revision;
- mediciones corregidas;
- eventos de auditoria.

## Relacion con otros modulos

El frontend React consume solamente este backend. El backend delega inferencia, modelos y reportes al AI Module FastAPI configurado con `PFI_AI_SERVICE_URL`. El contrato de estudio permite evolucionar hacia overlays reales, contornos de mascaras, landmarks y mediciones derivadas cuando se conecte la inferencia real.

## Contrato sagital real_baseline

Para `POST /api/ai/pipeline/run`, una request es estricta cuando `metadata.inferenceMode=real_baseline` y `metadata.allowContractFallback=false`. Si `allowContractFallback` falta en una request `real_baseline`, el backend agrega `false`. En modo estricto se exige `plane=sagittal`, `inputId` o `inputPath`, y `modelKey=sagittal_spider`; no se genera fallback demo ni `degraded-*` ante errores.

El flujo recomendado es subir primero el archivo con `POST /api/ai/inputs`, recibir el `inputId` opaco del AI Module y usarlo como campo top-level en `POST /api/ai/pipeline/run`. En modo estricto se acepta `inputId` o `inputPath`, pero no ambos; `inputId` es preferido y su ciclo de vida pertenece al AI Module.

El backend valida que la respuesta del AI Module preserve `modelVersion`, `artifactHash`, orientacion del volumen, measurements revisables, flags de seguridad y assets registrados. Las rutas internas del AI Module no se exponen: no se devuelve `inputPath` ni `metadata.sourcePath`, y el frontend recibe URLs relativas `/api/ai/assets/{runId}/{plane}/{assetName}`. El endpoint sigue siendo soporte tecnico revisable, no validacion clinica.

Para una prueba local real, usar `scripts/run_sagittal_real_backend_e2e.ps1` con `RUN_BACKEND_REAL_E2E=1`, `PFI_E2E_INPUT_PATH` y `PFI_BACKEND_BEARER_TOKEN`. El script llama solamente al backend, nunca directo al AI Module.
