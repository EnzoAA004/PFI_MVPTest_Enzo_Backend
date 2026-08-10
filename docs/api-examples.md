# Recorrido práctico de la API

Esta guía muestra el flujo de producto a través del backend. Swagger/OpenAPI sigue siendo la referencia formal de los 66 endpoints y sus schemas; este documento explica cómo encadenar las operaciones principales.

Los ejemplos usan Bash y `curl`. En PowerShell, usar `curl.exe` y adaptar la asignación de variables. Sustituir los valores entre `<...>` por los devueltos por la operación anterior.

```bash
BASE_URL="${BASE_URL:-http://localhost:8080}"
```

Todas las operaciones salvo liveness, autenticación y OpenAPI requieren `Authorization: Bearer <token>`.

## 1. Comprobar la salud del backend

### Qué hace

Verifica únicamente que el proceso backend está vivo. No revela el estado de PostgreSQL ni del AI Module.

### Precondiciones

- Backend levantado.

### Request

```bash
curl "$BASE_URL/api/system/health"
```

### Resultado esperado

```json
{"status":"ok"}
```

### Errores relevantes

- Error de conexión: el backend no está escuchando en `BASE_URL`.

Después de obtener un token se puede consultar la integración con IA:

```bash
curl "$BASE_URL/api/ai/health" \
  -H "Authorization: Bearer $TOKEN"
```

Si el backend está vivo pero el AI Module no responde, esta segunda operación puede devolver HTTP 200 con `backendStatus: "up"`, `aiModuleAvailable: false` y `degradedMode: true`.

## 2. Obtener un token de evaluación local

### Qué hace

Emite directamente un token con roles `ADMIN`, `DOCTOR` y `REVIEWER`. Es el camino más corto para recorrer el sistema local; no existe cuando el modo demo está deshabilitado.

### Precondiciones

- Stack local o registry levantado.
- `PFI_AUTH_DEMO_ENABLED=true`.

### Request

```bash
curl -X POST "$BASE_URL/api/auth/demo-doctor"
```

### Resultado esperado

```json
{
  "accessToken": "eyJ...",
  "refreshToken": "...",
  "tokenType": "Bearer",
  "expiresInSeconds": 3600,
  "user": {"email":"doctor.demo@pfi.local","roles":["ADMIN","DOCTOR","REVIEWER"]}
}
```

Copiar el valor recibido:

```bash
TOKEN="<accessToken>"
```

### Errores relevantes

- `401`: el modo demo está deshabilitado. No debe habilitarse en un entorno público.

## 3. Login de una cuenta existente

### Qué hace

Valida email y contraseña. Puede devolver tokens directamente o un `challengeId` si la cuenta necesita verificación o segundo factor.

### Precondiciones

- Cuenta registrada.
- Para operaciones profesionales, cuenta verificada y aprobada.

### Request

```bash
curl -X POST "$BASE_URL/api/auth/login" \
  -H "Content-Type: application/json" \
  -d '{"email":"profesional@example.org","password":"<password>"}'
```

Si la respuesta contiene `challengeId`, completar la verificación:

```bash
curl -X POST "$BASE_URL/api/auth/verify-login" \
  -H "Content-Type: application/json" \
  -d '{"challengeId":"<challengeId>","code":"<code>"}'
```

### Resultado esperado

La respuesta final tiene el mismo contrato `TokenResponse` del ejemplo demo. En desarrollo, `devVerificationCode` sólo aparece si `PFI_AUTH_EXPOSE_DEV_CODES=true`.

### Errores relevantes

- `401`: credenciales inválidas, código vencido o cuenta no disponible.
- Un token con rol efectivo `PENDING_APPROVAL` no habilita operaciones profesionales.

## 4. Cargar un estudio DICOM completo

### Qué hace

Envía un ZIP, clasifica sus series y devuelve un `studyId` junto con `inputId` opacos por plano/ponderación. Es la ingesta recomendada para el flujo completo.

### Precondiciones

- Token profesional.
- ZIP de estudio DICOM de-identificado.

### Request

```bash
CASE_ID="case-001"

curl -X POST "$BASE_URL/api/ai/studies?caseId=$CASE_ID" \
  -H "Authorization: Bearer $TOKEN" \
  -F "file=@./study.zip;type=application/zip"
```

### Resultado esperado

```json
{
  "caseId":"case-001",
  "studyId":"study-...",
  "seriesFound":[{"inputId":"inp_...","plane":"sagittal","sliceCount":24,"analyzable":true}],
  "sagittal":{"inputId":"inp_...","plane":"sagittal","sliceCount":24},
  "axial":{"inputId":"inp_...","plane":"axial","sliceCount":30},
  "warnings":[],
  "humanReviewRequired":true
}
```

Guardar los IDs disponibles:

```bash
SAGITTAL_INPUT_ID="<sagittal.inputId>"
AXIAL_INPUT_ID="<axial.inputId>"
```

### Errores relevantes

- `400`: ZIP inválido o sin series utilizables.
- `413`: supera `PFI_AI_STUDY_UPLOAD_MAX_BYTES`.
- `401`/`403`: token ausente o sin rol profesional activo.

## 5. Cargar un input individual

### Qué hace

Registra un archivo de una sola serie/plano y devuelve su `inputId`. Es útil cuando las series ya fueron separadas.

### Precondiciones

- Token profesional.
- Archivo soportado, por ejemplo `.mha`.

### Request

```bash
curl -X POST "$BASE_URL/api/ai/inputs?caseId=$CASE_ID&plane=sagittal" \
  -H "Authorization: Bearer $TOKEN" \
  -F "file=@./sagittal.mha"
```

### Resultado esperado

```json
{"inputId":"inp_...","caseId":"case-001","plane":"sagittal","format":"mha","size":1234567}
```

### Errores relevantes

- `400`: plano inválido, archivo vacío o formato no soportado.
- `413`: archivo demasiado grande.

## 6. Consultar la worklist de estudios

### Qué hace

Lista estudios de-identificados, estado, planos, última corrida y estado de revisión.

### Precondiciones

- Token profesional.
- PostgreSQL disponible en los modos operativos.

### Request

```bash
curl "$BASE_URL/api/studies" \
  -H "Authorization: Bearer $TOKEN"
```

### Resultado esperado

```json
{
  "status":"ok",
  "source":"postgres-domain",
  "dataOrigin":"database",
  "items":[{"caseId":"case-001","planes":["sagittal","axial"],"reviewStatus":"pendiente"}],
  "humanReviewRequired":true
}
```

### Errores relevantes

- `503 DATABASE_UNAVAILABLE`: no se pudo consultar PostgreSQL.

## 7. Ejecutar el pipeline de un plano

### Qué hace

Ejecuta el flujo sagital sobre un `inputId`. El ejemplo usa modo estricto para que un fallo del AI Module sea un error y no un resultado degradado.

### Precondiciones

- Token profesional.
- `SAGITTAL_INPUT_ID` obtenido en la ingesta.

### Request

```bash
curl -X POST "$BASE_URL/api/ai/pipeline/run" \
  -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" \
  -d "{\"caseId\":\"$CASE_ID\",\"plane\":\"sagittal\",\"modelKey\":\"sagittal_spider\",\"inputId\":\"$SAGITTAL_INPUT_ID\",\"metadata\":{\"inferenceMode\":\"real_baseline\",\"allowContractFallback\":false}}"
```

### Resultado esperado

Respuesta resumida de una ejecución real:

```json
{
  "runId":"<planeRunId>",
  "caseId":"case-001",
  "status":"completed",
  "aiModuleAvailable":true,
  "degradedMode":false,
  "humanReviewRequired":true
}
```

### Errores relevantes

- `400 BAD_REQUEST`: payload incompleto o inconsistente.
- `502 AI_CONTRACT_VIOLATION`: respuesta estricta degradada o incompatible.
- `504 AI_TIMEOUT`: se agotó `PFI_AI_TIMEOUT_SECONDS`.

## 8. Ejecutar el análisis multiplanar

### Qué hace

Ejecuta el flujo sagital y, si se proporciona, axial. Devuelve un `runId` raíz para revisión y un `runId` por plano para assets y exportación DICOM.

### Precondiciones

- Token profesional.
- Input sagital; axial es opcional.

### Request

```bash
curl -X POST "$BASE_URL/api/ai/multiplanar/run" \
  -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" \
  -d "{\"caseId\":\"$CASE_ID\",\"sagittalInputId\":\"$SAGITTAL_INPUT_ID\",\"axialInputId\":\"$AXIAL_INPUT_ID\",\"sagittalModelKey\":\"sagittal_spider\",\"axialModelKey\":\"axial_t2_alkafri\",\"allowContractFallback\":false,\"metadata\":{\"inferenceMode\":\"real_baseline\"}}"
```

Si no hay axial, omitir `axialInputId`; no enviarlo vacío.

### Resultado esperado

```json
{
  "runId":"<multiplanarRunId>",
  "caseId":"case-001",
  "requestedInferenceMode":"real_baseline",
  "effectiveInferenceMode":"real_baseline",
  "planes":{
    "sagittal":{"runId":"<sagittalPlaneRunId>","effectiveInferenceMode":"real_baseline","degradedMode":false},
    "axial":{"runId":"<axialPlaneRunId>","effectiveInferenceMode":"real_baseline","degradedMode":false}
  },
  "degradedMode":false,
  "humanReviewRequired":true
}
```

Guardar `runId` y los IDs de cada plano.

### Errores relevantes

- `400 BAD_REQUEST`: no hay ningún plano o los IDs son incoherentes.
- `502 AI_MULTIPLANAR_CONTRACT_VIOLATION`: una corrida estricta quedó mixed/fallback/degradada.
- `503 UPSTREAM_UNAVAILABLE`: AI Module no disponible en modo estricto.
- `504 AI_TIMEOUT`: timeout de inferencia.

## Cómo interpretar la inferencia

HTTP 200 significa que el backend procesó la solicitud; no garantiza que se ejecutó un modelo.

Para `pipeline/run`:

```json
{
  "status":"pipeline_degraded_fallback",
  "runId":"degraded-...",
  "aiModuleAvailable":false,
  "degradedMode":true,
  "humanReviewRequired":true
}
```

Esta respuesta no es inferencia. Un consumidor debe exigir `degradedMode=false` y `aiModuleAvailable=true` antes de presentarla como resultado del modelo.

Para `multiplanar/run`, comprobar la raíz y cada plano. `effectiveInferenceMode: "contract"`, `mixed`, un valor fallback o `degradedMode=true` indican que no se obtuvo una corrida real completa. En modo estricto esas condiciones producen error; fuera de modo estricto pueden formar parte de una respuesta 200 contractual.

## 9. Consultar resultados persistidos

### Qué hace

Recupera las corridas asociadas al caso, incluidos IDs por plano, modo efectivo y revisión.

### Precondiciones

- Token profesional.
- `CASE_ID` usado en una corrida persistida.

### Request

```bash
curl "$BASE_URL/api/studies/$CASE_ID/runs" \
  -H "Authorization: Bearer $TOKEN"
```

### Resultado esperado

```json
{
  "status":"ok",
  "caseId":"case-001",
  "runs":[{
    "runId":"<multiplanarRunId>",
    "effectiveInferenceMode":"real_baseline",
    "sagittalRunId":"<sagittalPlaneRunId>",
    "axialRunId":"<axialPlaneRunId>",
    "reviewStatus":"pendiente"
  }],
  "humanReviewRequired":true
}
```

### Errores relevantes

- `503 DATABASE_UNAVAILABLE`: PostgreSQL no está disponible.

Un reporte técnico de una corrida de plano puede consultarse así:

```bash
curl "$BASE_URL/api/ai/agent/report/<planeRunId>" \
  -H "Authorization: Bearer $TOKEN"
```

El reporte también puede ser HTTP 200 con `degradedMode=true` si no existe materialización upstream; debe interpretarse con la misma regla anterior.

## 10. Descargar un asset

### Qué hace

Obtiene una imagen pública de la corrida mediante el proxy autenticado del backend. Conviene usar la URL entregada en `planes.<plane>.assets` en vez de construirla manualmente.

### Precondiciones

- Token profesional.
- `planeRunId` real y nombre público.

### Request

```bash
curl "$BASE_URL/api/ai/assets/<planeRunId>/sagittal/overlay.png" \
  -H "Authorization: Bearer $TOKEN" \
  --output overlay.png
```

### Resultado esperado

Archivo PNG. El header `X-PFI-Asset-Source` puede indicar `postgres` o `ai-module-backfill` cuando el backend sirve un snapshot durable.

### Errores relevantes

- `403 ACCESS_DENIED`: asset interno no publicable, como `mask.npy` o `confidence.npy`.
- `404 ASSET_CONTENT_UNAVAILABLE`: corrida o asset inexistente.

## 11. Registrar la revisión profesional

### Qué hace

Persiste la decisión sobre el `multiplanarRunId`, el profesional, comentarios y correcciones. El ejemplo acepta la corrida sin correcciones.

### Precondiciones

- Token con rol profesional activo.
- `multiplanarRunId` persistido.

### Request

```bash
MULTIPLANAR_RUN_ID="<multiplanarRunId>"

curl -X POST "$BASE_URL/api/ai/runs/$MULTIPLANAR_RUN_ID/review" \
  -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" \
  -d '{"reviewStatus":"accepted","reviewer":"dr-demo","comments":"Evidencia revisada por profesional.","corrections":[]}'
```

### Resultado esperado

```json
{
  "multiplanarRunId":"<multiplanarRunId>",
  "reviewStatus":"accepted",
  "reviewer":"dr-demo",
  "reviewedAt":"2026-08-09T20:00:00Z",
  "comments":"Evidencia revisada por profesional.",
  "corrections":[]
}
```

### Errores relevantes

- `400 INVALID_REVIEW_STATUS`: estado fuera de `pending`, `accepted`, `observed`, `rejected`, `edited`.
- `400 REVIEWER_REQUIRED`: una decisión final no identifica profesional.
- `400 REVIEW_COMMENT_REQUIRED`: `observed` o `rejected` sin comentario descriptivo.
- `404 RUN_NOT_FOUND`: `multiplanarRunId` inexistente.
- `503 DATABASE_UNAVAILABLE`: no se pudo persistir la revisión.

## 12. Exportar DICOM SEG y SR

### Qué hace

Descarga objetos DICOM generados para una corrida de plano: segmentación SEG y reporte de mediciones SR. Se usa el `runId` del plano, no el `multiplanarRunId` raíz.

### Precondiciones

- Token profesional.
- `sagittalPlaneRunId` o `axialPlaneRunId` de una corrida real.
- Serie de origen compatible disponible en el AI Module.

### Request

```bash
PLANE_RUN_ID="<sagittalPlaneRunId>"

curl "$BASE_URL/api/ai/runs/$PLANE_RUN_ID/sagittal/segmentation.dcm" \
  -H "Authorization: Bearer $TOKEN" \
  --output segmentation.dcm

curl "$BASE_URL/api/ai/runs/$PLANE_RUN_ID/sagittal/measurements.sr.dcm" \
  -H "Authorization: Bearer $TOKEN" \
  --output measurements.sr.dcm
```

### Resultado esperado

Dos archivos con `Content-Type: application/dicom` y `Content-Disposition` seguro definido por el backend.

### Errores relevantes

- `400`: `planeRunId` o plano inválido.
- Error upstream: la corrida no tiene datos suficientes para materializar SEG/SR o el AI Module no está disponible.

## Referencia formal

Para endpoints adicionales, campos completos y códigos de error exactos del contrato publicado:

- Swagger UI: `$BASE_URL/swagger-ui/index.html`
- OpenAPI JSON: `$BASE_URL/v3/api-docs`
