# Cloud Environment Variables

| Variable | Requerida | Default local | Ejemplo cloud | Descripcion |
| --- | --- | --- | --- | --- |
| `PORT` | No | `8080` | `8080` o valor inyectado por la plataforma | Puerto HTTP del backend Spring Boot. |
| `PFI_AI_SERVICE_URL` | Si | `http://localhost:8000` | `https://pfi-ai-module.example.com` | URL base del AI Module FastAPI. |
| `PFI_AI_TIMEOUT_SECONDS` | No | `60` | `60` | Timeout para llamadas HTTP al AI Module. |
| `PFI_CORS_ALLOWED_ORIGINS` | Si para frontend cloud | `http://localhost:5173,http://localhost:3000` | `https://frontend.example.com` | Origenes permitidos para el frontend React. Separar varios con coma. |

## Ejemplo Render/Railway

```text
PFI_AI_SERVICE_URL=https://pfi-ai-module.example.com
PFI_AI_TIMEOUT_SECONDS=60
PFI_CORS_ALLOWED_ORIGINS=https://pfi-frontend.example.com
```

`PORT` normalmente lo define la plataforma. Si se configura manualmente, debe coincidir con el puerto expuesto por el servicio.
