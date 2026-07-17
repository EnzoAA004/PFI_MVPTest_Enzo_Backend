# DEV-002a - Docker Backend

Imagen Docker para el backend Spring Boot del PFI.

## Imagen

El `Dockerfile` es multi-stage:

- Build: `maven:3.9-eclipse-temurin-21`
- Runtime: `eclipse-temurin:21-jre-alpine`
- Jar: `/app/app.jar`
- Puerto expuesto: `8080`

## Variables de entorno

Configuracion principal:

| Variable | Requerida | Ejemplo | Descripcion |
| --- | --- | --- | --- |
| `PORT` | No | `8080` | Puerto HTTP del backend. |
| `PFI_AI_SERVICE_URL` | Si | `http://host.docker.internal:8000` | URL base del AI Module. |
| `PFI_AI_TIMEOUT_SECONDS` | No | `180` | Timeout de llamadas al AI Module. |
| `PFI_PERSISTENCE_MODE` | Si para PostgreSQL | `postgres` | Usa persistencia JDBC PostgreSQL. Valor local fallback: `memory`. |
| `DATABASE_URL` | Si para PostgreSQL | `jdbc:postgresql://host.docker.internal:5432/pfi_backend?user=pfi&password=change-me` | JDBC URL de PostgreSQL o URL `postgres://...`. |
| `PFI_AUTH_JWT_SECRET` | Si en entornos compartidos | `change-me-with-a-long-random-secret` | Secreto de firma JWT. No usar valores demo en produccion. |
| `PFI_CORS_ALLOWED_ORIGINS` | No | `http://localhost:5173,http://localhost:3000` | Origins permitidos del frontend. |

No se hornean secretos ni credenciales en la imagen. Pasarlos siempre con variables de entorno o secretos del orquestador.

## Build

```powershell
docker build -t pfi-backend:dev .
```

## Run con PostgreSQL local de prueba

Ejemplo creando una red y un PostgreSQL efimero:

```powershell
docker network create pfi-dev

docker run --rm --name pfi-postgres --network pfi-dev `
  -e POSTGRES_DB=pfi_backend `
  -e POSTGRES_USER=pfi `
  -e POSTGRES_PASSWORD=pfi_dev_password `
  -p 54329:5432 `
  postgres:16-alpine
```

En otra terminal:

```powershell
docker run --rm --name pfi-backend --network pfi-dev `
  -p 8080:8080 `
  -e PORT=8080 `
  -e PFI_PERSISTENCE_MODE=postgres `
  -e DATABASE_URL="jdbc:postgresql://pfi-postgres:5432/pfi_backend?user=pfi&password=pfi_dev_password" `
  -e PFI_AI_SERVICE_URL=http://host.docker.internal:8000 `
  -e PFI_AUTH_JWT_SECRET=change-me-local-dev-only `
  pfi-backend:dev
```

Health:

```powershell
Invoke-RestMethod http://localhost:8080/api/ai/health
```

Si el AI Module no esta levantado, el backend debe seguir vivo y devolver `backendStatus=up` con `aiModuleAvailable=false`.

## .dockerignore

El contexto excluye `target/`, `.git/`, archivos `.env`, logs, secretos, pesos de modelo (`.pt`, `.pth`), arrays/imagenes clinicas pesadas y otros artefactos locales.
