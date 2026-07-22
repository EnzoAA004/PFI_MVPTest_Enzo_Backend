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

## POST /api/ai/multiplanar/run

Reenvia `POST /multiplanar/run` al AI Module usando inputs registrados por plano.

Request:

```json
{
  "caseId": "case-001",
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

`allowContractFallback` se propaga en `metadata`. Si el AI Module rechaza una corrida con fallback deshabilitado, el backend devuelve el error semantico y no genera una respuesta 200 degradada.

Para compatibilidad, el backend deserializa tanto `planes.sagittal` como el alias historico `planes.sagital`. La respuesta publica principal incluye `planes.sagittal`; tambien conserva alias de lectura legacy.

`inferenceMode` es el modo reportado por el AI Module. `effectiveInferenceMode` es el campo que consume el Frontend para habilitar evaluacion. Por plano se resuelve con esta precedencia sin perder trazabilidad:

1. `effectiveInferenceMode`;
2. `inferenceMode`;
3. `aiOutput.inferenceMode`;
4. `metadata.inferenceMode`.

En `real_baseline` estricto dual (`metadata.inferenceMode=real_baseline` y `allowContractFallback=false`) el backend valida que root, sagital y axial sean reales. El sagital debe usar `modelKey=sagittal_spider`, `modelVersion=sagittal-spider-final-v1`, `artifactHash=cf11dcc0ad77a7c787e64a796a2fd7398ef906add461cef4b3d61f1a5238e944`, orientacion SPIDER `[17,512,512] -> [512,512,17]`, `selectedAxis=2`, spacing positivo en `mm`, `humanReviewRequired=true` y `notClinicalDiagnosis=true`. El axial se valida como plano real independiente con `modelKey=axial_t2_alkafri`, sin exigir SHA/version sagital.

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

Actualiza la revision profesional local in-memory.

Estados validos:

- `pendiente`
- `aceptado`
- `observado`
- `descartado`

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

- `404` si `multiplanarRunId` no existe.
- `400` si `reviewStatus` no pertenece al enum final.
- `400` si `reviewer` esta vacio.
- `reviewedAt` lo asigna el servidor.
- Las correcciones guardan un snapshot minimo `beforeValue`/`afterValue`; el versionado completo de mediciones queda para BE-008.

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
