# Backend API Contract

Base URL local: `http://localhost:8080`

Todas las respuestas vinculadas a resultados del AI Module deben conservar `humanReviewRequired=true`. El backend provee soporte tecnico revisable y no diagnostico clinico.

## Roles y acciones sensibles

El backend usa JWT con `roles` en claims. Roles existentes:

- `ADMIN`: acciones administrativas.
- `DOCTOR` / `REVIEWER`: profesional habilitado para revisar corridas.
- `PENDING_APPROVAL`: cuenta profesional pendiente, sin acceso a acciones sensibles.

Endpoints protegidos:

- `POST /api/ai/models/sync`: requiere `ADMIN`.
- `GET /api/system/diagnostics`: requiere `ADMIN`.
- `POST/PUT/GET /api/ai/runs/{multiplanarRunId}/review`: requiere `REVIEWER`, `DOCTOR` o `ADMIN`.

Si el rol es insuficiente, la respuesta es `403` con mensaje semantico `Rol insuficiente`. Los intentos denegados se auditan como `access.denied` sin tokens, credenciales ni datos identificables. No existe endpoint de cache clear en este backend al momento de BE-010.

## GET /api/ai/health

Consulta `GET /health` del AI Module.

Response esperada:

```json
{
  "status": "ok",
  "humanReviewRequired": true
}
```

Si el AI Module no esta disponible:

```json
{
  "status": "down",
  "aiModuleAvailable": false,
  "humanReviewRequired": true,
  "message": "502 BAD_GATEWAY ..."
}
```

## GET /api/ai/models

Consulta `GET /models` del AI Module y devuelve su contrato sin modificar.

Response esperada:

```json
[
  {
    "key": "baseline",
    "name": "Baseline sagittal segmentation",
    "version": "1.0"
  }
]
```

## POST /api/ai/pipeline/run

Consulta `POST /pipeline/run` del AI Module.

Request:

```json
{
  "caseId": "case-001",
  "plane": "sagittal",
  "modelKey": "baseline",
  "inputId": "inp_case_001_sagittal",
  "metadata": {
    "source": "local-test"
  }
}
```

Para el flujo recomendado, primero subir el archivo con `POST /api/ai/inputs`, conservar el `inputId` opaco devuelto por el AI Module, y enviarlo como campo top-level en `POST /api/ai/pipeline/run`. El backend no transforma ese `inputId` en `inputPath`, no persiste rutas internas y no las devuelve al frontend.

Response esperada:

```json
{
  "runId": "run-001",
  "humanReviewRequired": true,
  "measurements": [],
  "review": {
    "runId": "run-001",
    "status": "pendiente",
    "notes": "",
    "reviewer": "",
    "updatedAt": "2026-06-30T00:00:00Z"
  }
}
```

## POST /api/ai/inputs

Reenvia un archivo de input al `POST /inputs` multipart del AI Module.

Request multipart:

- `file`: archivo `.npy`, `.png`, `.jpg`, `.jpeg`, `.bmp`, `.tif`, `.tiff`, `.mha`, `.mhd` o `.dcm`
- `caseId`: identificador del caso
- `plane`: `sagittal` o `axial`

Response esperada:

```json
{
  "inputId": "inp_case_001_sagittal",
  "caseId": "case-001",
  "plane": "sagittal",
  "format": "npy",
  "size": 123456
}
```

El backend valida extension, plano y tamano antes de reenviar. La respuesta no expone paths internos ni `inputPath`. El ciclo de vida del `inputId` depende del registro del AI Module.

## GET /api/ai/assets/{planeRunId}/{plane}/{assetName}

Sirve assets publicos derivados de una corrida real. El frontend nunca llama directo al AI Module.

- `assetName` permitido: `input.png`, `overlay.png`, `mask-preview.png`.
- Rechaza traversal, `.npy`, `.pt/.pth`, HTML/JSON u otros nombres antes de exponer contenido.
- Si el payload fue preservado en PostgreSQL responde `200 image/png` con `Content-Length`, `ETag`, `Cache-Control: private` y `X-PFI-Asset-Source: postgres`.
- Si existe metadata pero falta payload, intenta un backfill unico desde el AI Module; si resulta exitoso responde con `X-PFI-Asset-Source: ai-module-backfill`.
- Si el output temporal ya no existe responde `404` con `code=ASSET_CONTENT_UNAVAILABLE`, `runId`, `plane`, `assetName`, `traceId`, `humanReviewRequired=true` y `notClinicalDiagnosis=true`.

El backend preserva solo PNG de revision deidentificados (`input.png`, `overlay.png`, `mask-preview.png`) hasta `PFI_ASSET_STORAGE_MAX_BYTES` bytes, default `5242880` (5 MB). No persiste MHA/MHD/DICOM, uploads raw, `.npy`, `.pt/.pth`, notebooks, checkpoints ni paths internos.

## POST /api/ai/multiplanar/run

Reenvia `POST /multiplanar/run` al AI Module usando inputs registrados por plano.
El request publico acepta `studyMetadata` de-identificada, pero el backend construye un `MultiplanarRunRequestDto` tecnico sanitizado para el AI Module y no envia `subjectRef`, `studyDate`, `description` ni `reviewPriority` upstream.
Antes de llamar al AI Module, el backend ejecuta un preflight de metadata: normaliza `caseId`, `subjectRef` y `reviewPriority`, consulta si el estudio ya existe y bloquea errores semanticos. El orden del flujo es: request publico -> validar metadata/conflictos -> construir request tecnico sanitizado -> llamar AI Module -> validar respuesta IA -> persistir estudio, inputs, corrida y assets. Un `subjectRef` invalido, un conflicto o una base no disponible no disparan inferencia.

Request:

```json
{
  "caseId": "CASE-SPIDER-101-20260726",
  "studyMetadata": {
    "subjectRef": "SPIDER-101",
    "studyDate": "2026-07-26",
    "modality": "MRI",
    "description": "RM lumbar sagital T2",
    "reviewPriority": "medium"
  },
  "sagittalInputId": "input-sag-001",
  "axialInputId": "input-ax-001",
  "sagittalModelKey": "sagittal_spider",
  "axialModelKey": "axial_t2_alkafri",
  "allowContractFallback": false,
  "metadata": {
    "inferenceMode": "real_baseline"
  }
}
```

Response esperada:

```json
{
  "status": "multiplanar_run_ready",
  "schemaVersion": "multiplanar-run-v1",
  "runId": "multi-001",
  "traceId": "trace-001",
  "caseId": "case-001",
  "workspaceMode": "dual_plane_with_3d_context",
  "requestedInferenceMode": "real_baseline",
  "effectiveInferenceMode": "real_baseline",
  "planes": {
    "sagittal": {
      "runId": "run-sag-001",
      "plane": "sagittal",
      "modelKey": "sagittal_spider",
      "modelVersion": "sagittal-spider-final-v1",
      "artifactHash": "cf11dcc0ad77a7c787e64a796a2fd7398ef906add461cef4b3d61f1a5238e944",
      "inferenceMode": "real_baseline",
      "requestedInferenceMode": "real_baseline",
      "effectiveInferenceMode": "real_baseline",
      "inputId": "input-sag-001",
      "landmarks": [
        {
          "name": "L4_left_pedicle",
          "x": 124.2,
          "y": 210.5,
          "z": 42,
          "confidence": 0.94
        }
      ],
      "measurements": {
        "canalAreaMm2": 82.4,
        "measurementsDerivedFromPredictionMask": true
      },
      "assets": {
        "input.png": "/api/ai/assets/run-sag-001/sagittal/input.png",
        "overlay.png": "/api/ai/assets/run-sag-001/sagittal/overlay.png",
        "mask-preview.png": "/api/ai/assets/run-sag-001/sagittal/mask-preview.png"
      },
      "metadata": {
        "inputShapeNative": [17, 512, 512],
        "inputShapeCanonical": [512, 512, 17],
        "inputOrientationTransform": "move_axis_0_to_last",
        "selectedAxis": 2,
        "selectedSlice": 9,
        "sliceCount": 17,
        "inPlaneSpacing": [0.7, 0.7],
        "inPlaneSpacingUnit": "mm"
      }
    },
    "axial": {
      "runId": "run-ax-001",
      "plane": "axial",
      "modelKey": "axial_t2_alkafri",
      "inferenceMode": "real_baseline",
      "effectiveInferenceMode": "real_baseline",
      "landmarks": [
        {
          "name": "canal_center",
          "x": 93.3,
          "y": 118.8,
          "sliceIndex": 18,
          "confidence": 0.9
        }
      ],
      "measurements": {
        "leftForamenMm": 3.1,
        "rightForamenMm": 3.4,
        "measurementsDerivedFromPredictionMask": true
      },
      "assets": {
        "input.png": "/api/ai/assets/run-ax-001/axial/input.png",
        "overlay.png": "/api/ai/assets/run-ax-001/axial/overlay.png",
        "mask-preview.png": "/api/ai/assets/run-ax-001/axial/mask-preview.png"
      }
    }
  },
  "assets": {
    "workspace": "workspace.json"
  }
}
```

`subjectRef` es opcional para compatibilidad. Si se informa debe tener 3 a 64 caracteres y usar solo letras, numeros, guion, guion bajo o punto. Espacios, `@`, barras y traversal devuelven `400 INVALID_SUBJECT_REFERENCE`. Si un `caseId` ya tiene otro `subjectRef` no nulo, el backend devuelve `409 SUBJECT_REFERENCE_CONFLICT`.
`reviewPriority` acepta `low|medium|high` o alias `baja|media|alta`; se persiste como `low|medium|high`. Un valor desconocido devuelve `400 INVALID_REVIEW_PRIORITY`. Si la base falla durante el preflight o upsert de metadata, responde `503 DATABASE_UNAVAILABLE`.

`allowContractFallback` se propaga en `metadata`. Si el AI Module rechaza una corrida con fallback deshabilitado, el backend devuelve el error semantico y no genera una respuesta 200 degradada.

Para compatibilidad, el backend deserializa tanto `planes.sagittal` como el alias historico `planes.sagital`. La respuesta publica principal incluye `planes.sagittal`; tambien conserva alias de lectura legacy.

`inferenceMode` es el modo reportado por el AI Module. `effectiveInferenceMode` es el campo que consume el Frontend para habilitar evaluacion. Por plano se resuelve con esta precedencia sin perder trazabilidad:

1. `effectiveInferenceMode`;
2. `inferenceMode`;
3. `aiOutput.inferenceMode`;
4. `metadata.inferenceMode`.

En `real_baseline` estricto (`metadata.inferenceMode=real_baseline` y `allowContractFallback=false`) el sagital es obligatorio y el axial es opcional/experimental. El backend valida que el sagital sea real: `modelKey=sagittal_spider`, `modelVersion=sagittal-spider-final-v1`, `artifactHash=cf11dcc0ad77a7c787e64a796a2fd7398ef906add461cef4b3d61f1a5238e944`, orientacion SPIDER `[17,512,512] -> [512,512,17]`, `selectedAxis=2`, spacing positivo en `mm`, `humanReviewRequired=true` y `notClinicalDiagnosis=true`. Si se solicita axial, se valida como plano real independiente con `modelKey=axial_t2_alkafri`; si no se solicita, `planes.axial` puede venir ausente/null y el workspace puede reportar `mixed`, `axialRunReady=false` o `dualRunReady=false`.

Si root queda `mixed`, algun plano queda `contract`/fallback, `degradedMode=true`, faltan inputs estrictos, o el contrato no preserva inferencia real, el backend devuelve error y no persiste el run como completed. Violaciones de contrato devuelven `502` con `code=AI_MULTIPLANAR_CONTRACT_VIOLATION`.

El payload publico elimina rutas internas recursivamente (`inputPath`, `sourcePath`, `imagePath`, `outputFiles`, cualquier `path`, `/tmp`, `/content`, rutas Windows, Colab, Google Drive y `models/final`). Se conservan `inputId`, runIds, hashes, orientacion, spacing, quality, mediciones y flags de revision. Assets raw (`mask.npy`, `confidence.npy`) se eliminan del payload publico; solo se publican `input.png`, `overlay.png` y `mask-preview.png` via `/api/ai/assets/{planeRunId}/{plane}/{assetName}` usando el `runId` del plano, no el runId multiplanar.

## GET /api/ai/assets/{runId}/{plane}/{assetName}

Streamea assets visuales del AI Module via `GET /assets/{runId}/{plane}/{assetName}`. El frontend debe usar siempre este proxy del backend y no llamar directo al AI Module.

Assets permitidos:

- `input.png`
- `overlay.png`
- `mask-preview.png`

Response `200`:

- `Content-Type`: propagado desde el AI Module, esperado `image/png`
- Body: bytes del PNG

El backend rechaza traversal y nombres fuera de allowlist antes de llamar al AI Module. No sirve assets raw (`mask.npy`, `confidence.npy`) ni pesos de modelo (`.pt`, `.pth`) y no expone paths internos. Los `403` y `404` del AI Module se preservan como errores HTTP.

## GET /api/ai/agent/report/{runId}

Consulta `GET /agent/report/{runId}` del AI Module y agrega la revision local.

Response esperada:

```json
{
  "runId": "run-001",
  "humanReviewRequired": true,
  "agentDecision": {
    "label": "technical-review-required",
    "confidence": 0.82
  },
  "review": {
    "runId": "run-001",
    "status": "pendiente",
    "notes": "",
    "reviewer": "",
    "updatedAt": "2026-06-30T00:00:00Z"
  }
}
```

## PATCH /api/ai/review/{runId}

Endpoint legacy mantenido por compatibilidad. Delega en el endpoint canonico `POST/PUT /api/ai/runs/{multiplanarRunId}/review` y persiste la revision en el modelo `domain_*`; no escribe nuevas revisiones reales en `review_statuses`.

Estados validos:

- `pendiente`
- `aceptado`
- `observado`
- `descartado`
- `rechazado`
- `editado`

Request:

```json
{
  "status": "observado",
  "notes": "Revisar medicion L4-L5",
  "reviewer": "dr-demo"
}
```

Response:

```json
{
  "runId": "run-001",
  "status": "observado",
  "notes": "Revisar medicion L4-L5",
  "reviewer": "dr-demo",
  "updatedAt": "2026-06-30T00:00:00Z"
}
```

### Modo sagital `real_baseline` estricto

Una request es estricta cuando `metadata.inferenceMode` es `real_baseline` y `metadata.allowContractFallback` es `false`. Si `allowContractFallback` no fue enviado en una request `real_baseline`, el backend agrega `false`; si fue enviado como `true`, se conserva para compatibilidad demo/contract.

En modo estricto:

- `plane` debe ser `sagittal`;
- `modelKey` se normaliza a `sagittal_spider`;
- `inputId` o `inputPath` es obligatorio, pero no ambos;
- `inputId` es el flujo recomendado y debe venir de `POST /api/ai/inputs`;
- `inputId` o `inputPath` no puede apuntar a `demo/<caseId>`;
- los errores 4xx del AI Module se preservan como errores 4xx;
- timeout devuelve `504`;
- conexion rechazada o error 5xx upstream devuelve `502`;
- una respuesta 2xx con contrato invalido devuelve `502` con `code=AI_CONTRACT_VIOLATION`;
- no se crea `pipeline_degraded_fallback`, `degraded-*`, mediciones ficticias ni `agentDecision` sintetico como exito.

El contrato sagital esperado se configura por variables:

- `PFI_SAGITTAL_EXPECTED_MODEL_KEY=sagittal_spider`
- `PFI_SAGITTAL_EXPECTED_MODEL_VERSION=sagittal-spider-final-v1`
- `PFI_SAGITTAL_EXPECTED_MODEL_SHA256=cf11dcc0ad77a7c787e64a796a2fd7398ef906add461cef4b3d61f1a5238e944`
- `PFI_SAGITTAL_EXPECTED_RELEASE_ID=sagittal_spider_final_v1`
- `PFI_SAGITTAL_EXPECTED_RELEASE_CONTENT_SHA256=7420ad4271fe634c970b2a543d1ef8fb1437888c99ca8bd5733a06e5f63e3e7e`
- `PFI_SAGITTAL_EXPECTED_RELEASE_MANIFEST_SHA256=d36d0c4fe183ba9a98f0a3471486be5dee1cf1fa820dc32b3a50177ce322be21`

La respuesta estricta se sanitiza antes de llegar al frontend: se eliminan rutas internas (`/tmp`, `/content`, rutas Windows, `models/final`, `inputPath`, `metadata.sourcePath`, `metadata.outputFiles`) y los assets publicos se reescriben como `/api/ai/assets/{runId}/{plane}/{assetName}`. No se publican `mask.npy` ni `confidence.npy`. Si la corrida usa `inputId` y el AI Module lo devuelve, debe coincidir con el `inputId` enviado.

Flujo frontend recomendado:

1. `POST /api/ai/inputs` con multipart.
2. Guardar solo el `inputId` opaco de la respuesta.
3. `POST /api/ai/pipeline/run` con `caseId`, `plane=sagittal`, `modelKey=sagittal_spider`, `inputId` y `metadata.inferenceMode=real_baseline`.
4. Consumir assets via `/api/ai/assets/{runId}/{plane}/{assetName}`.
5. Registrar revision profesional. La salida es soporte tecnico revisable, no diagnostico clinico.

## POST /api/ai/models/sync

Endpoint administrativo con rol `ADMIN`. Reenvia `POST /models/sync?force=false|true` al AI Module y valida el item sagital individual.

Se acepta exito solo cuando el status global es `synced_verified` o `existing_release_verified` y el item `sagittal_spider` coincide con `releaseId`, hashes de release, `modelSha256`, `source=gcs_verified_release`, flags de sync/verificacion y `gcsReadOnly=true`. Si falla la verificacion, responde error controlado y no afirma readiness.

La respuesta exitosa agrega:

```json
{
  "sagittalReadyForRealInference": true,
  "proxiedByBackend": true,
  "humanReviewRequired": true,
  "notClinicalDiagnosis": true
}
```

## POST/PUT /api/ai/runs/{multiplanarRunId}/review

Registra o actualiza la revision profesional persistida de una corrida multiplanar. El run debe existir previamente en la persistencia BE-005b/BE-006.

Enum final de `reviewStatus`:

- `pending`: estado inicial del run persistido.
- `accepted`: aceptado por el profesional.
- `observed`: observado por el profesional; equivale al texto de producto "observado".
- `rejected`: rechazado por el profesional.
- `edited`: aceptado con ediciones/correcciones registradas.

Alias aceptados en request:

- `pendiente` -> `pending`
- `aceptado` -> `accepted`
- `observado` -> `observed`
- `descartado` / `rechazado` -> `rejected`
- `editado` -> `edited`

Request:

```json
{
  "reviewStatus": "observed",
  "reviewer": "dra-demo",
  "comments": "Medicion observada para ajuste academico.",
  "corrections": [
    {
      "measurementId": "canalAreaMm2",
      "label": "Area del canal",
      "beforeValue": {
        "value": 82.4,
        "unit": "mm2"
      },
      "afterValue": {
        "value": 85.1,
        "unit": "mm2"
      },
      "comment": "Ajuste manual por borde parcial."
    }
  ]
}
```

Response:

```json
{
  "multiplanarRunId": "multi-001",
  "traceId": "trace-001",
  "reviewStatus": "observed",
  "reviewer": "dra-demo",
  "reviewedAt": "2026-07-16T12:00:00Z",
  "comments": "Medicion observada para ajuste academico.",
  "corrections": [
    {
      "measurementId": "canalAreaMm2",
      "label": "Area del canal",
      "beforeValue": {
        "value": 82.4,
        "unit": "mm2"
      },
      "afterValue": {
        "value": 85.1,
        "unit": "mm2"
      },
      "comment": "Ajuste manual por borde parcial."
    }
  ]
}
```

Reglas:

- `404 RUN_NOT_FOUND` si `multiplanarRunId` no existe.
- `400 INVALID_REVIEW_STATUS` si `reviewStatus` no pertenece al enum final ni a sus alias.
- `400 REVIEWER_REQUIRED` si `reviewer` esta vacio para decisiones finales.
- `400 REVIEW_COMMENT_REQUIRED` si `observed` o `rejected` no tienen comentario descriptivo.
- `pending` puede guardarse como borrador con comentarios/correcciones y sin `reviewer`; no asigna `reviewedAt` final.
- `reviewedAt` lo asigna el servidor para `accepted`, `observed`, `rejected` y `edited`.
- Las correcciones guardan un snapshot minimo `beforeValue`/`afterValue`; el versionado completo de mediciones queda para BE-008.
- La actualizacion de `domain_study_runs`, `domain_review_corrections` y `domain_audit_events` ocurre en una unica transaccion.

Backfill legacy:

- `docs/migrations/V20260726_010_legacy_review_backfill.sql` migra revisiones historicas desde `review_statuses` a `domain_study_runs` cuando `review_statuses.run_id = domain_study_runs.multiplanar_run_id`.
- No sobreescribe una revision domain mas nueva; conserva `reviewer`, `notes -> comments` y `updated_at -> reviewed_at` para estados finales.
- Registra conteos en `domain_legacy_review_backfill_runs` y un evento `legacy.review.backfill`.

## GET /api/ai/runs/{multiplanarRunId}/review

Consulta la revision profesional persistida actual de una corrida multiplanar. Devuelve el mismo shape de response que `POST/PUT`.

## GET /api/ai/audit-events

Consulta eventos de auditoria persistidos por `traceId` o `entityId`.

Ejemplos:

- `GET /api/ai/audit-events?traceId=trace-001`
- `GET /api/ai/audit-events?entityId=multi-001`

Response:

```json
[
  {
    "id": "audit-event-uuid",
    "actor": "backend",
    "action": "multiplanar.run.completed",
    "entityId": "multi-001",
    "traceId": "trace-001",
    "timestamp": "2026-07-16T12:00:00Z",
    "metadata": {
      "caseId": "CASE-DEMO",
      "effectiveInferenceMode": "real_baseline"
    }
  }
]
```

La metadata se sanea antes de persistir: no debe contener tokens, secretos, paths internos, blobs ni datos identificables.

## P8-A: Worklist y detalle desde PostgreSQL

Con `pfi.persistence.mode=postgres`, los endpoints de estudios leen exclusivamente las tablas `domain_*`:

- `GET /api/studies`
- `GET /api/studies/{caseId}`
- `GET /api/studies/{caseId}/runs`

No hay fallback demo ni memoria para esos endpoints. Una base vacia devuelve `items: []`; una base PostgreSQL no disponible devuelve `503 DATABASE_UNAVAILABLE` con `traceId`, `humanReviewRequired=true` y `notClinicalDiagnosis=true`.

`GET /api/studies` devuelve `source=postgres-domain`, `dataOrigin=database`, `summary` y filas con:

- `caseId`, `subjectRef`, `studyDate`, `status`, `planes`, `primaryPlane`, `latestRunId`, `modelKey`, `modelStatus`, `reviewStatus`, `priority`, `createdAt`, `updatedAt`, `dataOrigin`.
- Aliases legacy: `plane` equivale a `primaryPlane`; `runId` equivale a `latestRunId`.

`GET /api/studies/{caseId}` agrega inputs, corridas, artefactos agrupados por plano, mediciones por plano, correcciones y auditoria persistida. Las mediciones preservan `aiValue`; las correcciones profesionales se devuelven separadas para no destruir el valor de IA.

`GET /api/studies/{caseId}/runs` lista corridas persistidas ordenadas por `created_at DESC`. El `runId` publico es siempre `multiplanar_run_id`; `databaseId` conserva el UUID interno.

`PUT /api/studies/{caseId}/metadata` completa o actualiza metadata de-identificada de un estudio existente. Requiere rol profesional (`REVIEWER`, `DOCTOR` o `ADMIN`). `caseId` inexistente devuelve `404 STUDY_NOT_FOUND`; `subjectRef` invalido devuelve `400 INVALID_SUBJECT_REFERENCE`; conflicto de referencia devuelve `409 SUBJECT_REFERENCE_CONFLICT`; prioridad desconocida devuelve `400 INVALID_REVIEW_PRIORITY`; base no disponible devuelve `503 DATABASE_UNAVAILABLE`. La actualizacion de metadata no modifica el lifecycle `status` existente (`ready`, `completed`, etc.).

Request:

```json
{
  "subjectRef": "SPIDER-101",
  "studyDate": "2026-07-26",
  "modality": "MRI",
  "description": "RM lumbar sagital T2",
  "reviewPriority": "medium"
}
```

Response:

```json
{
  "status": "ok",
  "study": {
    "caseId": "CASE-SPIDER-101-20260726",
    "subjectRef": null,
    "studyDate": null,
    "modality": null,
    "description": null,
    "priority": "media",
    "dataOrigin": "database"
  },
  "humanReviewRequired": true,
  "notClinicalDiagnosis": true
}
```

`GET /api/subjects/{subjectRef}/history` consulta historial longitudinal desde `domain_*`, sin seed demo ni tablas legacy. Multiples `caseId` pueden compartir el mismo `subjectRef`; la respuesta se ordena por `studyDate DESC NULLS LAST` y luego `createdAt DESC`. Si no hay estudios devuelve `200` con `studies: []`.

Las tablas legacy `studies` y `study_runs` quedan pendientes de eliminacion fisica. `PostgresStudyCatalogService` esta deshabilitado por defecto y solo se crea con `pfi.legacy.study-catalog.enabled=true`; con la propiedad ausente o `false` no ejecuta `migrate()` ni `seedDemoCatalog()`.

Response:

```json
{
  "status": "ok",
  "source": "postgres-domain",
  "dataOrigin": "database",
  "subjectRef": "SPIDER-101",
  "deidentified": true,
  "studies": [
    {
      "caseId": "CASE-SPIDER-101-20260726",
      "studyDate": "2026-07-26",
      "modality": "MRI",
      "description": "RM lumbar sagital T2",
      "priority": "media",
      "latestRunId": "multi-001",
      "planes": ["sagittal"],
      "modelKey": "sagittal_spider",
      "reviewStatus": "aceptado",
      "measurementsByPlane": {
        "sagittal": []
      },
      "corrections": []
    }
  ],
  "summary": {
    "totalStudies": 1,
    "pending": 0,
    "completed": 1,
    "observed": 0,
    "withStudyDate": 1
  },
  "humanReviewRequired": true,
  "notClinicalDiagnosis": true
}
```

El historial devuelve solo mediciones reales presentes en `metrics_snapshot`; si faltan, se omiten. No calcula `lordosisAngle`, `canalDiameter`, `averageDiscHeight`, `l45DiscHeight` ni otros valores aproximados.

Mapeos de API:

- `reviewStatus`: `pending -> pendiente`, `accepted -> aceptado`, `observed -> observado`, `rejected -> descartado`, `edited -> observado`.
- `priority`: `high -> alta`, `medium -> media`, `low -> baja`.

Los artefactos no exponen paths internos. Cada asset se publica como proxy relativo:

```text
/api/ai/assets/{planeRunId}/{plane}/{assetName}
```

Los endpoints demo quedan reservados a `pfi.demo.enabled=true` y devuelven `dataOrigin=demo`. `ReviewStoreService` y `PostgresReviewStoreService` son legado para endpoints anteriores de review y no alimentan el worklist P8-A.
