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

## Endpoints

- `GET /api/ai/health`: consulta el health del AI Module.
- `GET /api/ai/models`: lista modelos disponibles en el AI Module.
- `POST /api/ai/pipeline/run`: ejecuta el pipeline remoto y marca revision humana requerida.
- `GET /api/ai/agent/report/{runId}`: obtiene el reporte del agente y agrega revision local.
- `PATCH /api/ai/review/{runId}`: actualiza la revision profesional local.

## Ejemplos curl

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
curl http://localhost:8080/api/ai/agent/report/run-001
```

```bash
curl -X PATCH http://localhost:8080/api/ai/review/run-001 \
  -H "Content-Type: application/json" \
  -d '{"status":"observado","notes":"Revisar medicion L4-L5","reviewer":"dr-demo"}'
```

## Relacion con otros modulos

El frontend React consume solamente este backend. El backend delega inferencia, modelos y reportes al AI Module FastAPI configurado con `PFI_AI_SERVICE_URL`. La persistencia incluida es minima e in-memory, orientada a conservar el estado de revision profesional durante la ejecucion local.
