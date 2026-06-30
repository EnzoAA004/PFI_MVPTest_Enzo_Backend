# PFI MVP Test Enzo - Backend

Backend Spring Boot del producto final.

## Arquitectura

```text
Frontend React -> Backend Spring Boot -> AI Module FastAPI
```

## Requisitos

- Java 17+
- Maven 3.9+
- AI Module levantado en `http://localhost:8000`

## Variable principal

```bash
export PFI_AI_SERVICE_URL=http://localhost:8000
```

## Ejecucion local

```bash
mvn spring-boot:run
```

## Endpoints

- `GET /api/ai/health`
- `GET /api/ai/models`
- `POST /api/ai/pipeline/run`
- `GET /api/ai/agent/report/{runId}`
- `PATCH /api/ai/review/{runId}`
