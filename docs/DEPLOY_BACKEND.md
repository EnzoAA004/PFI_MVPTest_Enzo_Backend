# Deploy Backend

Este backend Spring Boot es el servicio intermedio del PFI:

```text
Frontend React -> Spring Boot Backend -> Python FastAPI AI Module
```

El backend no ejecuta modelos de IA. Consume el AI Module por HTTP, normaliza el contrato para frontend, preserva `humanReviewRequired=true` y mantiene la revision profesional.

## Variables Necesarias

| Variable | Ejemplo | Uso |
| --- | --- | --- |
| `PORT` | `8080` | Puerto HTTP usado por Spring Boot. Render/Railway suelen inyectarlo automaticamente. |
| `PFI_AI_SERVICE_URL` | `https://pfi-ai-module.example.com` | URL base del AI Module FastAPI desplegado. |
| `PFI_AI_TIMEOUT_SECONDS` | `60` | Timeout del cliente HTTP hacia el AI Module. |
| `PFI_CORS_ALLOWED_ORIGINS` | `https://frontend.example.com` | Origenes permitidos para el frontend React. Separar varios con coma. |

## Render Web Service

Opcion Docker:

1. Crear un nuevo Web Service conectado a este repositorio.
2. Elegir deploy con Dockerfile.
3. Configurar variables:
   - `PFI_AI_SERVICE_URL=https://url-del-ai-module`
   - `PFI_AI_TIMEOUT_SECONDS=60`
   - `PFI_CORS_ALLOWED_ORIGINS=https://url-del-frontend`
4. Render asigna `PORT`; la app lo toma con `server.port=${PORT:8080}`.
5. Health de backend:

```bash
curl https://url-del-backend/api/ai/health
```

## Railway

Opcion Docker:

1. Crear un servicio desde GitHub apuntando a este repositorio.
2. Railway usa `railway.json` y el `Dockerfile` del repo para autodeploy.
3. Dejar habilitado el autodeploy del branch elegido en Railway.
4. Configurar variables:
   - `PFI_AI_SERVICE_URL=https://url-del-ai-module`
   - `PFI_AI_TIMEOUT_SECONDS=180`
   - `PFI_CORS_ALLOWED_ORIGINS=https://url-del-frontend`
   - `PFI_AUTH_JWT_SECRET=<secret-largo-generado-en-railway>`
   - `PFI_PERSISTENCE_MODE=postgres`
   - `DATABASE_URL=<PostgreSQL URL de Railway>`
5. Railway define `PORT`; Spring Boot lo usa automaticamente.
6. El healthcheck configurado es `GET /api/ai/health`.
7. Probar:

```bash
curl https://url-del-backend/api/ai/health
```

No commitear `.env` reales ni secretos. Para Postgres, usar una variable `DATABASE_URL` del plugin/servicio de Railway o copiar la URL interna que Railway exponga para el backend.

## Docker Local

Build:

```bash
docker build -t pfi-backend .
```

Run contra AI Module local:

```bash
docker run --rm -p 8080:8080 \
  -e PFI_AI_SERVICE_URL=http://host.docker.internal:8000 \
  -e PFI_AI_TIMEOUT_SECONDS=60 \
  -e PFI_CORS_ALLOWED_ORIGINS=http://localhost:5173,http://localhost:3000 \
  pfi-backend
```

Run contra AI Module desplegado:

```bash
docker run --rm -p 8080:8080 \
  -e PFI_AI_SERVICE_URL=https://url-del-ai-module \
  -e PFI_CORS_ALLOWED_ORIGINS=https://url-del-frontend \
  pfi-backend
```

## CORS Cloud

Para frontend desplegado:

```text
PFI_CORS_ALLOWED_ORIGINS=https://url-del-frontend
```

Para pruebas con varios origenes:

```text
PFI_CORS_ALLOWED_ORIGINS=http://localhost:5173,http://localhost:3000,https://url-del-frontend
```

No usar `*` si el frontend va a manejar credenciales o sesiones en una etapa posterior.
