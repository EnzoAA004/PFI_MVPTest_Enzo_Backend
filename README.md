# PFI RM Lumbar — Backend y stack del producto

Prototipo académico para análisis asistido de resonancias magnéticas lumbares. El sistema permite cargar estudios de-identificados, ejecutar procesamiento sagital y axial, consultar evidencia y mediciones, y registrar una revisión profesional. No emite diagnósticos ni reemplaza el criterio clínico.

Este repositorio contiene el backend Spring Boot y los archivos Docker Compose que integran los tres componentes del producto:

```text
Frontend React → Backend Spring Boot → AI Module FastAPI
                         ↓
                    PostgreSQL
```

El frontend consume exclusivamente este backend. La salida de IA es técnica, revisable y mantiene `humanReviewRequired=true`.

## Arquitectura

El backend es la frontera HTTP del sistema: autentica usuarios, aplica permisos, valida contratos, coordina el AI Module, persiste estudios/corridas/revisiones y sirve los assets permitidos al navegador.

- [Arquitectura actual](docs/architecture.md)
- [Ejemplos prácticos de la API](docs/api-examples.md)
- [Contrato formal OpenAPI](#documentación-de-la-api)

Los documentos `P9_*`, `P10_*`, `*_EVIDENCE.md` y equivalentes dentro de `docs/` son evidencia histórica de iteraciones y decisiones. Se conservan para trazabilidad, pero no reemplazan esta guía operativa ni OpenAPI.

## Requisitos

Para el Quick Start con imágenes publicadas sólo se necesita:

- Docker Engine o Docker Desktop;
- Docker Compose v2 (`docker compose`).

Para trabajar desde código fuente:

- Java 17;
- Maven 3.8.3 o superior;
- Python 3.12 recomendado para el AI Module;
- Node.js 22 y npm para el frontend;
- los tres repositorios clonados como carpetas hermanas.

Maven Enforcer rechaza un JDK distinto de Java 17.

## Quick Start

### Modo registry

Este modo descarga las imágenes publicadas en GHCR y no requiere clonar los tres repositorios ni instalar Java, Python o Node.

```bash
mkdir pfi-rm-lumbar
cd pfi-rm-lumbar
curl -LO https://raw.githubusercontent.com/EnzoAA004/PFI_MVPTest_Enzo_Backend/main/compose.yml
curl -Lo .env https://raw.githubusercontent.com/EnzoAA004/PFI_MVPTest_Enzo_Backend/main/.env.registry.example
docker compose pull
docker compose up -d
docker compose ps
```

Servicios publicados:

- frontend: <http://localhost:8088>
- backend: <http://localhost:8080>
- Swagger UI: <http://localhost:8080/swagger-ui/index.html>
- PostgreSQL: `localhost:54329`

Comprobar el backend:

```bash
curl http://localhost:8080/api/system/health
```

La cuenta demo está habilitada por `.env.registry.example` para evaluación local. Debe deshabilitarse si el stack queda accesible desde otra máquina.

### Desarrollo local desde source

La estructura esperada es:

```text
tesis/
├── PFI_MVPTest_Enzo_Backend/
├── PFI_MVPTest_Enzo_AImodule/
└── PFI_MVPTest_Enzo_Frontend/
```

Desde el repositorio backend:

```bash
cp .env.example .env
docker compose --env-file .env -f compose.local.yml up --build
```

En PowerShell, el primer comando es `Copy-Item .env.example .env`.

Este modo construye los tres servicios desde source y usa PostgreSQL. Los checkpoints externos de los clasificadores degenerativos son opcionales para levantar el stack; si no están presentes, sólo esas capacidades quedan no disponibles.

Para ejecutar únicamente el backend:

```bash
mvn spring-boot:run
```

Sin `PFI_PERSISTENCE_MODE=postgres`, el backend usa la implementación en memoria destinada a desarrollo y tests. Los modos Compose y producción configuran PostgreSQL.

## Documentación de la API

Con el backend levantado:

- Swagger UI: <http://localhost:8080/swagger-ui/index.html>
- OpenAPI JSON: <http://localhost:8080/v3/api-docs>

OpenAPI es la referencia formal de endpoints, DTOs y respuestas. [docs/api-examples.md](docs/api-examples.md) es el recorrido práctico de autenticación, ingesta, inferencia, assets, revisión y exportación DICOM.

## Configuración

No se replica aquí el catálogo completo de variables:

- `.env.registry.example`: stack con imágenes GHCR;
- `.env.example`: desarrollo local desde source;
- [docs/CLOUD_ENVIRONMENT_VARIABLES.md](docs/CLOUD_ENVIRONMENT_VARIABLES.md): despliegue del backend.

Variables principales:

| Variable | Uso |
|---|---|
| `PFI_IMAGE_TAG` | `latest` o `sha-<commit>` para las tres imágenes del stack registry. |
| `PFI_AUTH_JWT_SECRET` | Firma de tokens. Debe reemplazarse fuera de una prueba local. |
| `PFI_AUTH_DEMO_ENABLED` | Habilita el token demo; sólo debe usarse localmente. |
| `PFI_AI_SERVICE_URL` | URL interna del AI Module cuando el backend se ejecuta fuera de Compose. |
| `PFI_AI_SERVICE_MULTIPLANAR_CONTRACT_VERSION` | Contrato interno `v1` o `v2`; Compose usa `v2`. |
| `BACKEND_PUBLIC_URL` | URL del backend accesible desde el navegador. |
| `PFI_AI_STUDY_UPLOAD_MAX_BYTES` | Límite del ZIP de estudio completo. |

## Tests

```bash
mvn compile
mvn verify
```

`mvn verify` ejecuta la suite, construye el JAR y genera el reporte JaCoCo. Los tests de PostgreSQL usan Testcontainers y requieren un daemon Docker disponible.

## Cobertura

El reporte local se genera en `target/site/jacoco/index.html`. GitHub Actions publica el porcentaje de líneas y ramas en el summary de `Backend CI` y adjunta el artifact `jacoco-report`.

No hay un quality gate porcentual configurado: la cobertura se mide y publica, pero cualquier threshold futuro debe justificarse por separado.

## Imágenes Docker

El workflow publica en GHCR:

- `ghcr.io/enzoaa004/pfi-backend`
- `ghcr.io/enzoaa004/pfi-ai-module`
- `ghcr.io/enzoaa004/pfi-frontend`

`latest` sigue a `main`. Para reproducibilidad, usar `PFI_IMAGE_TAG=sha-<commit-completo>` con un tag publicado por CI.

## Limitaciones conocidas

- Los clasificadores subarticular y degenerativo multitarea requieren checkpoints externos que no se redistribuyen en Git ni en la imagen registry. Sus endpoints informan indisponibilidad sin impedir el resto del sistema.
- La disponibilidad y los términos/licencia de redistribución de esos artifacts externos siguen pendientes de verificación documental.
- Fuera del modo estricto, `pipeline/run` y `multiplanar/run` pueden responder HTTP 200 en modo degradado o contractual. Un 200 confirma que la petición fue procesada, no que hubo inferencia real. Hay que comprobar los campos descritos en [docs/api-examples.md](docs/api-examples.md#cómo-interpretar-la-inferencia).
- Es un prototipo académico y no un dispositivo médico. Toda salida requiere revisión profesional.

## Troubleshooting

- `401` en `/api/auth/demo-doctor`: comprobar `PFI_AUTH_DEMO_ENABLED=true` y recrear el backend.
- Backend `unhealthy`: usar `/api/system/health`, revisar `docker compose logs backend` y el estado de PostgreSQL.
- `aiModuleAvailable=false` o `degradedMode=true`: revisar `docker compose logs ai-module` y `/api/ai/health` con token.
- `503` en clasificadores degenerativos: verificar el checkpoint externo y su variable; es el comportamiento esperado cuando no se distribuye el artifact.
- Puertos ocupados: cambiar `BACKEND_PORT`, `FRONTEND_PORT` o `POSTGRES_PORT` en `.env`.
- Para detener sin borrar datos: `docker compose down`. `docker compose down -v` también elimina los volúmenes de PostgreSQL, uploads y outputs.

## Repositorios

- [Backend](https://github.com/EnzoAA004/PFI_MVPTest_Enzo_Backend)
- [AI Module](https://github.com/EnzoAA004/PFI_MVPTest_Enzo_AImodule)
- [Frontend](https://github.com/EnzoAA004/PFI_MVPTest_Enzo_Frontend)
