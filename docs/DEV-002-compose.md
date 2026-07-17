# DEV-002 - Docker Compose Local Completo

Stack local para levantar con un comando:

- PostgreSQL 16
- AI Module FastAPI
- Backend Spring Boot
- Frontend React/Vite servido por nginx

## Comando Unico

Desde `PFI_MVPTest_Enzo_Backend`:

```powershell
docker compose --env-file .env.example up -d --build
```

Para uso local editable, copiar primero:

```powershell
Copy-Item .env.example .env
docker compose up -d --build
```

## Variables

Las variables viven en `.env.example` como placeholders locales, sin secretos reales:

| Variable | Uso |
| --- | --- |
| `POSTGRES_DB` | Nombre de la DB PostgreSQL. |
| `POSTGRES_USER` | Usuario local de PostgreSQL. |
| `POSTGRES_PASSWORD` | Password local placeholder. Cambiar en `.env`. |
| `POSTGRES_PORT` | Puerto host de PostgreSQL. |
| `AI_MODULE_PORT` | Puerto host del AI Module. |
| `AI_MODEL_DIR` | Ruta host con checkpoints/model cards del AI Module. |
| `BACKEND_PORT` | Puerto host del backend. |
| `BACKEND_PUBLIC_URL` | URL que usa el navegador/frontend para llamar al backend. Debe ser alcanzable desde el host, por ejemplo `http://localhost:8080`. |
| `PFI_AI_TIMEOUT_SECONDS` | Timeout del backend hacia el AI Module. |
| `PFI_AUTH_JWT_SECRET` | Secreto JWT placeholder. Cambiar en `.env`; no commitear valores reales. |
| `PFI_CORS_ALLOWED_ORIGINS` | Origen permitido del frontend, por defecto `http://localhost:8088`. |
| `FRONTEND_PORT` | Puerto host del frontend. |

## Wiring

- `backend -> postgres`: `jdbc:postgresql://postgres:5432/${POSTGRES_DB}` por red Docker interna.
- `backend -> ai-module`: `http://ai-module:8000` por red Docker interna.
- `frontend -> backend`: `BACKEND_URL=${BACKEND_PUBLIC_URL}`. El navegador corre en el host, por eso no debe usar `http://backend:8080`.
- `frontend /env.js`: generado al arrancar nginx con `window.__PFI_CONFIG__.API_BASE_URL`.

## Modelos .pt

Los checkpoints no se hornean en imagenes. Se montan read-only:

```yaml
${AI_MODEL_DIR:-../PFI_MVPTest_Enzo_AImodule/models/final}:/models/final:ro
```

El AI Module recibe:

```text
PFI_MODEL_DIR=/models/final
```

Antes de levantar, verificar que existan los checkpoints esperados en `AI_MODEL_DIR`, por ejemplo:

- `sagittal_spider_multiclass_final_best.pt`
- `axial_t2_alkafri_final_best.pt`

## Verificacion

```powershell
docker compose --env-file .env.example ps
Invoke-RestMethod http://localhost:8080/api/ai/health
Invoke-RestMethod http://localhost:8000/health
Invoke-RestMethod http://localhost:8088/env.js
```

Esperado:

- `postgres`, `ai-module`, `backend`, `frontend`: `healthy`.
- Backend health con `backendStatus=up`.
- Si el AI Module esta healthy y responde por red interna, `aiModuleAvailable=true`.
- `/env.js` debe apuntar a `http://localhost:8080`.

## Troubleshooting

- `backend` no queda healthy: revisar `docker compose logs backend`; suele ser DB no disponible, migraciones o `DATABASE_URL`.
- `aiModuleAvailable=false`: revisar `docker compose logs ai-module` y confirmar que `/health` responde dentro del contenedor.
- Frontend no llama al backend: revisar `http://localhost:8088/env.js`; debe contener `http://localhost:8080`, no `http://backend:8080`.
- Puerto ocupado: cambiar `BACKEND_PORT`, `FRONTEND_PORT`, `AI_MODULE_PORT` o `POSTGRES_PORT` en `.env`.
- Checkpoints ausentes: revisar `AI_MODEL_DIR`; los `.pt` deben estar en el host y montarse como volumen read-only.

## Apagar

```powershell
docker compose --env-file .env.example down
```

Para borrar datos locales de PostgreSQL:

```powershell
docker compose --env-file .env.example down -v
```
