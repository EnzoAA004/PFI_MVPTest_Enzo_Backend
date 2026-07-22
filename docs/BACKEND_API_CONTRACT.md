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
  "inputPath": "studies/case-001",
  "metadata": {
    "source": "local-test"
  }
}
```

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
  "inputId": "input-001",
  "caseId": "case-001",
  "plane": "sagittal",
  "format": "npy",
  "size": 123456
}
```

El backend valida extension, plano y tamano antes de reenviar. La respuesta no expone paths internos.

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
  "runId": "multi-001",
  "traceId": "trace-001",
  "effectiveInferenceMode": "real_baseline",
  "planes": {
    "sagital": {
      "runId": "run-sag-001",
      "effectiveInferenceMode": "real_baseline",
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
        "overlay": "overlay.png"
      }
    },
    "axial": {
      "runId": "run-ax-001",
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
        "overlay": "overlay.png"
      }
    }
  },
  "assets": {
    "workspace": "workspace.json"
  }
}
```

`allowContractFallback` se propaga en `metadata`. Si el AI Module rechaza una corrida con fallback deshabilitado, el backend devuelve el error semantico y no genera una respuesta 200 degradada.

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
- `inputPath` es obligatorio y no se autocompleta con `demo/<caseId>`;
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

La respuesta estricta se sanitiza antes de llegar al frontend: se eliminan rutas internas (`/tmp`, `/content`, rutas Windows, `models/final`, `metadata.sourcePath`, `metadata.outputFiles`) y los assets publicos se reescriben como `/api/ai/assets/{runId}/{plane}/{assetName}`. No se publican `mask.npy` ni `confidence.npy`.

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
