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
- `POST /api/ai/pipeline/run`: ejecuta el pipeline remoto y marca revision humana requerida.
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
curl -X POST http://localhost:8080/api/ai/pipeline/run \
  -H "Content-Type: application/json" \
  -H "Authorization: Bearer <token>" \
  -d '{"caseId":"case-001","plane":"sagittal","modelKey":"baseline","inputPath":"studies/case-001"}'
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
