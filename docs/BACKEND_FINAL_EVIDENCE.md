# Backend Final Evidence

Commit de referencia al crear esta evidencia: `5f952be`

## Proposito

Backend Spring Boot independiente para el PFI. Expone API REST al frontend React y consume el AI Module FastAPI por HTTP.

El backend no ejecuta IA, no emite diagnostico clinico y no toma decisiones medicas automaticas. Las respuestas tecnicas preservan `humanReviewRequired=true` y la revision profesional queda en estados controlados: `pendiente`, `aceptado`, `observado`, `descartado`.

## Endpoints

| Metodo | Endpoint | Descripcion |
| --- | --- | --- |
| `GET` | `/api/ai/health` | Estado del backend y disponibilidad del AI Module. |
| `GET` | `/api/ai/models` | Modelos publicados por el AI Module. |
| `POST` | `/api/ai/pipeline/run` | Ejecuta pipeline remoto en AI Module y agrega revision local. |
| `GET` | `/api/ai/agent/report/{runId}` | Recupera reporte tecnico del agente y revision local. |
| `PATCH` | `/api/ai/review/{runId}` | Actualiza revision profesional local. |

## Comandos Locales

```bash
mvn test
mvn spring-boot:run
```

Con AI Module local:

```bash
PFI_AI_SERVICE_URL=http://localhost:8000 mvn spring-boot:run
```

En PowerShell:

```powershell
$env:PFI_AI_SERVICE_URL="http://localhost:8000"
mvn spring-boot:run
```

## Docker

```bash
docker build -t pfi-backend .
docker run --rm -p 8080:8080 \
  -e PFI_AI_SERVICE_URL=http://host.docker.internal:8000 \
  -e PFI_CORS_ALLOWED_ORIGINS=http://localhost:5173,http://localhost:3000 \
  pfi-backend
```

## curl /api/ai/health

```bash
curl http://localhost:8080/api/ai/health
```

Respuesta esperada:

```json
{
  "backendStatus": "up",
  "aiModuleAvailable": true,
  "humanReviewRequired": true,
  "notClinicalDiagnosis": true
}
```

## curl /api/ai/models

```bash
curl http://localhost:8080/api/ai/models
```

Respuesta esperada: contrato del AI Module normalizado a camelCase para frontend.

## curl /api/ai/pipeline/run

```bash
curl -X POST http://localhost:8080/api/ai/pipeline/run \
  -H "Content-Type: application/json" \
  -d '{"caseId":"case-demo-001","plane":"sagittal","modelKey":"sagittal_spider","inputPath":"demo/case-demo-001","metadata":{"source":"backend-final-evidence"}}'
```

Campos esperados:

```json
{
  "runId": "string",
  "caseId": "case-demo-001",
  "modelKey": "sagittal_spider",
  "humanReviewRequired": true,
  "notClinicalDiagnosis": true,
  "review": {
    "status": "pendiente"
  }
}
```

## PATCH /api/ai/review/{runId}

```bash
curl -X PATCH http://localhost:8080/api/ai/review/RUN_ID \
  -H "Content-Type: application/json" \
  -d '{"status":"observado","notes":"Revision profesional pendiente de validacion final","reviewer":"demo"}'
```

Respuesta esperada:

```json
{
  "runId": "RUN_ID",
  "status": "observado",
  "notes": "Revision profesional pendiente de validacion final",
  "reviewer": "demo"
}
```

## Limitaciones

- La persistencia de revision profesional es in-memory; se reinicia con el proceso.
- El backend depende de `PFI_AI_SERVICE_URL` para consultar inferencia, modelos y reportes.
- Si el AI Module no tiene modelos o datos reales disponibles, puede responder salidas tecnicas de smoke/contrato.
- No hay autenticacion ni autorizacion productiva todavia.

## Aclaracion Metodologica

Este backend brinda soporte tecnico revisable. No produce diagnostico clinico, no recomienda tratamientos y no reemplaza el criterio profesional. Todo resultado debe ser revisado por una persona habilitada antes de cualquier uso medico.
