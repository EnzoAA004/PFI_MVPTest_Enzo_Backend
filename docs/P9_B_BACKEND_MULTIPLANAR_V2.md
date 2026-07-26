# P9-B: Backend consume el contrato multiplanar v2 del AI Module

Estado: implementado. Rollback disponible sin revertir commit ni migraciones.

## Objetivo

Hacer que el backend pueda consumir `POST /v2/multiplanar/run` (`schemaVersion=pfi.multiplanar-run.v2`)
de forma tipada, validada y persistida, manteniendo:

- el endpoint legacy `POST /multiplanar/run` (`schemaVersion=multiplanar-run-v1`) disponible;
- el contrato publico `POST /api/ai/multiplanar/run` sin cambios para el frontend P8;
- reversibilidad total via configuracion, sin tocar codigo ni base de datos.

## 1. Feature flag

Propiedad Spring: `pfi.ai-service.multiplanar-contract-version`
Variable Railway: `PFI_AI_SERVICE_MULTIPLANAR_CONTRACT_VERSION`
Valores permitidos: `v1` (default) | `v2`

`AiServiceProperties.resolvedMultiplanarContractVersion()` resuelve el valor a
`AiMultiplanarContractVersion.{V1,V2}`. Un valor desconocido (`v3`, `V2 `, etc. mas alla de
trim/case-insensitive) lanza `IllegalStateException` desde un metodo `@PostConstruct`,
lo que **impide el arranque de Spring Boot** con un mensaje claro. No hay fallback
automatico de v2 a v1 ni reintento contra el otro contrato ante error — el rollback es
exclusivamente operativo (cambiar la variable y redeploy).

## 2. Arquitectura de adapters

```
AiServiceOperations (puerto de dominio)
    CanonicalMultiplanarRun runMultiplanar(MultiplanarRunRequestDto request)
        |
        v
AiServiceClient (unico implementador)
    resuelve version -> construye request -> llama EXACTAMENTE un endpoint
        |
   +----+----+
   |         |
  v1        v2
   |         |
   v         v
POST /multiplanar/run      AiMultiplanarV2RequestMapper
   |                            |
   v                            v
MultiplanarRunResponseDto   POST /v2/multiplanar/run
   |                            |
   v                            v
MultiplanarRealBaselineContractValidator (si strict)   AiMultiplanarV2ResponseDto
   |                            |
   v                            v
AiMultiplanarV1ResponseAdapter   MultiplanarV2RealBaselineValidator (si strict)
   |                            |
   |                            v
   |                       AiMultiplanarV2ResponseAdapter
   |                            |
   +------------+---------------+
                |
                v
       CanonicalMultiplanarRun (unico tipo devuelto por el puerto)
```

Ningun DTO del AI Module (v1 o v2) cruza la interfaz `AiServiceOperations`. Todo lo
que hay "aguas abajo" del cliente (controlador, persistencia, auditoria) trabaja
exclusivamente con `CanonicalMultiplanarRun`/`CanonicalPlaneRun`.

Para exponer al frontend P8 el mismo contrato JSON de siempre, existe un mapeador de
presentacion **de salida**, no de dominio: `CanonicalMultiplanarRunLegacyPresenter`
convierte `CanonicalMultiplanarRun` -> `MultiplanarRunResponseDto` (el shape publico
`multiplanar-run-v1`), y el `MultiplanarRunResponsePresenter` existente sigue
sanitizando paths y reescribiendo URLs de assets como siempre. Este mapeador es
deliberadamente temporal — ver "Plan P9-C" abajo.

## 3. Request v2

`AiMultiplanarV2RequestMapper` construye el body v2 a partir del `MultiplanarRunRequestDto`
interno (ya sin `studyMetadata`, que el controlador nunca reenvia al AI Module en ninguna
version). Nunca incluye `studyMetadata`, `subjectRef`, `studyDate`, `modality`,
`description`, `reviewPriority`, `sagittalInputPath`/`axialInputPath`, `backendTraceId`,
`correlationId` ni metadata libre. Si un plano solo tiene `inputPath` (sin `inputId`),
la request v2 se rechaza con `400` — v2 solo admite inputs ya registrados por id.

Ejemplo de request v2 real (sagital estricto):

```json
{
  "caseId": "CASE-SPIDER-101-20260726",
  "traceId": "trace-9f1c2e",
  "inferenceMode": "real_baseline",
  "allowContractFallback": false,
  "planes": {
    "sagittal": {
      "inputId": "inp_sagittal_001",
      "modelKey": "sagittal_spider"
    },
    "axial": null
  },
  "options": {
    "sliceIndex": null,
    "sliceAxis": null,
    "sliceWindowRadius": 3,
    "inputOrientationTransform": null
  }
}
```

`traceId` se envia en `body.traceId` **y** en el header `X-Trace-Id`, siempre desde el
mismo valor de MDC (`TraceIdFilter`). `AiServiceClient` valida que ambos coincidan antes
de llamar al AI Module (`TraceIdConsistencyGuard`); un desvio lanza
`AiMultiplanarContractViolationException` (502 `AI_MULTIPLANAR_CONTRACT_VIOLATION`).

## 4. Modelo canonico

```java
CanonicalMultiplanarRun(
    status, schemaVersion, multiplanarRunId, traceId, caseId, workspaceMode,
    requestedInferenceMode, effectiveInferenceMode,
    requestedPlanes, completedPlanes, synthetic, fallbackReason,
    readiness, planes, threeD, quality, review, governance
)

CanonicalPlaneRun(
    planeRunId, plane, status, effectiveInferenceMode, synthetic, fallbackReason,
    model, input, coordinateSpace, series, assets, masks, landmarks, measurements, quality
)
```

`governance` es un record tipado (`humanReviewRequired`, `notClinicalDiagnosis`,
`deidentified`, `diagnosisGenerated`); el resto de las secciones variables (model,
input, coordinateSpace, series, assets, masks, landmarks, measurements, quality,
readiness, threeD, review) son `Map<String,Object>`/`List<Map<String,Object>>` —
deliberadamente flexibles para no atar el modelo interno a los nombres exactos de
cada version de wire, siempre que los adapters preserven los valores originales de IA.

- `AiMultiplanarV1ResponseAdapter`: adapta `MultiplanarRunResponseDto`. Reconstruye
  `model` combinando `modelKey`/`modelVersion` (campos sueltos en v1) con `modelArtifact`
  (que solo trae el hash), ya que v1 nunca los junta en un solo objeto.
- `AiMultiplanarV2ResponseAdapter`: adapta `AiMultiplanarV2ResponseDto` usando
  `ObjectMapper.convertValue` sobre cada sub-DTO tipado, preservando el 100% de los
  campos que el AI Module v2 realmente envio.

### Wire v2 vs modelo interno: nombres que NO coinciden a proposito

El contrato real `pfi.multiplanar-run.v2` (ver
`ai_service/pfi_ai_service/multiplanar_v2_models.py` en el AI Module) usa `runId` tanto
en la raiz como dentro de cada plano, y `model.key`/`model.version` para el modelo. El
backend **no** usa `@JsonAlias` para aceptar variantes: `AiMultiplanarV2ResponseDto` y
`AiPlaneRunV2Dto` declaran el campo `runId` tal cual llega del AI Module, y es el
*adapter* (`AiMultiplanarV2ResponseAdapter`), no el DTO, quien traduce al nombre interno:

| Capa                                    | Campo                                    |
|------------------------------------------|-------------------------------------------|
| Wire v2 (`AiMultiplanarV2ResponseDto`)   | `runId`                                    |
| Modelo interno (`CanonicalMultiplanarRun`)| `multiplanarRunId`                        |
| Wire v2 (`AiPlaneRunV2Dto`)               | `runId`                                    |
| Modelo interno (`CanonicalPlaneRun`)      | `planeRunId`                               |
| Wire v2 (`AiPlaneModelV2Dto`)             | `key`, `version`                           |
| Canonico (`plane.model()` map)            | `"key"`, `"version"` (se preservan tal cual)|
| Wire v2 (`AiReadinessV2Dto`)              | `sagittal`, `axial`, `dual`                |

`modelKey`/`modelVersion`/`sagittalReady`/`axialReady`/`dualRunReady`/`multiplanarRunId`
(a nivel raiz del wire)/`planeRunId` (a nivel raiz del plano wire) **no existen** en el
contrato real y estan explicitamente rechazados por `ignoreUnknown=false` en los DTOs
v2 — ver `AiMultiplanarV2ResponseContractTest` para los tests de rechazo.

Nota de compatibilidad: como el adapter v1 sigue poblando el mapa canonico `model` con
`modelKey`/`modelVersion` (nombres v1), los consumidores del mapa canonico
(`CanonicalMultiplanarRunLegacyPresenter`, `AiMultiplanarController`,
`MultiplanarRunPersistenceService`) leen primero `"key"`/`"version"` (v2) y caen a
`"modelKey"`/`"modelVersion"` (v1) solo si el primero no esta presente. Esto es un
detalle de implementacion del mapa interno, no un `@JsonAlias` en el wire.

## 5. Validacion estricta

Ambas validaciones solo se ejecutan cuando la request es "estricta"
(`metadata.inferenceMode=real_baseline` y `allowContractFallback=false`), y corren
**dentro de `AiServiceClient`**, antes de devolver el modelo canonico — ningun resultado
invalido llega al controlador ni a persistencia.

- **v1**: `MultiplanarRealBaselineContractValidator` (sin cambios de P8), corre sobre el
  DTO crudo `MultiplanarRunResponseDto` antes de adaptar a canonico.
- **v2**: `MultiplanarV2RealBaselineValidator` (nuevo), corre sobre `CanonicalMultiplanarRun`.
  Valida root (`status=completed`, `schemaVersion=pfi.multiplanar-run.v2`,
  `multiplanarRunId` no vacio y prefijado `multi-`, `caseId` coincide, `traceId` no
  vacio, `requestedInferenceMode`/`effectiveInferenceMode=real_baseline`,
  `synthetic=false`, `fallbackReason=null`, `requestedPlanes`/`completedPlanes`
  coinciden con lo pedido, `workspaceMode` coherente, `review.required=true`,
  `review.status=pending`, y las 4 flags de `governance`) y el plano sagital
  (`status=ready`, `planeRunId` distinto de `multiplanarRunId`, `model.key/version/hash`
  exactos, `model.baselineReady/availableForRealInference/manifestValid=true`,
  `input.inputId` coincide, `nativeShape`/`canonicalShape` validas, `sliceCount>0`,
  `selectedSliceIndex` en rango, `coordinateSpace` > 0x0, `series` no vacia, assets
  `input.png`/`overlay.png` presentes con `relativePath` seguro — sin `..`, sin URL
  externa, sin `localhost`/`cloudflare`/`host.docker.internal` —, masks/landmarks/
  measurements con `id`/`classKey`|`labelKey` validos, y ausencia de `reviewerValue`
  en las mediciones de IA).

El chequeo de **3 masks / 3 landmarks / 9 measurements** exactos es una politica
congelada especifica de `sagittal_spider`/`sagittal-spider-final-v1` — se aplica
solo si `model.key`/`model.version` coinciden exactamente con ese release; no es una
regla universal para futuros modelos.

Axial: si no fue solicitado, `planes.axial` debe ser `null`; si fue solicitado, debe
estar presente. Si el AI Module responde `MODEL_NOT_READY` para axial, la excepcion
estructurada corta el flujo antes de construir cualquier resultado canonico — no hay
"corrida parcial" que persistir. El backend nunca promueve el artifact axial ni altera
su `trainingStatus`.

Cualquier violacion lanza `AiMultiplanarContractViolationException` -> `502
AI_MULTIPLANAR_CONTRACT_VIOLATION`, conservando el `traceId` de la request. No se
persiste nada del resultado rechazado.

## 6. Errores estructurados del AI Module (v2)

`AiStructuredErrorV2Dto` tipa el error del AI Module (`status`, `schemaVersion`, `code`,
`message`, `traceId`, `caseId`, `requestedPlanes`, `details`, `governance`).
`AiMultiplanarV2ErrorCodeMapper` traduce `code` a un status/code estable del backend:

| AI Module `code`              | HTTP | Backend `code`                  |
|--------------------------------|------|----------------------------------|
| `INVALID_MULTIPLANAR_REQUEST`  | 400  | `AI_INVALID_REQUEST`             |
| `NO_PLANE_REQUESTED`           | 400  | `AI_NO_PLANE_REQUESTED`          |
| `INPUT_NOT_FOUND`              | 404  | `AI_INPUT_NOT_FOUND`             |
| `MODEL_NOT_FOUND`               | 404  | `AI_MODEL_NOT_FOUND`             |
| `MODEL_PLANE_MISMATCH`         | 409  | `AI_MODEL_PLANE_MISMATCH`        |
| `MODEL_NOT_READY`              | 409  | `AI_MODEL_NOT_READY`             |
| `CONTRACT_FALLBACK_DISABLED`   | 502  | `AI_CONTRACT_FALLBACK_DISABLED`  |
| `REAL_INFERENCE_FAILED`        | 502  | `AI_REAL_INFERENCE_FAILED`       |
| `INVALID_MULTIPLANAR_RESPONSE` | 502  | `AI_INVALID_RESPONSE`            |
| `UNSUPPORTED_INFERENCE_MODE`   | 400  | `AI_UNSUPPORTED_INFERENCE_MODE`  |
| desconocido / cuerpo no estructurado | 502 | `AI_MODULE_ERROR`          |
| timeout                        | 504  | `AI_MODULE_TIMEOUT`              |

`AiMultiplanarUpstreamException` conserva `code`, `message` (saneado, una linea,
truncado a 180 caracteres), el `traceId` del AI Module y el HTTP status — nunca el
stack trace ni el body completo del upstream.

## 7. Persistencia canonica

`MultiplanarRunPersistenceService.persistSuccessfulRun(request, studyMetadata,
CanonicalMultiplanarRun)` es la unica firma — ya no recibe DTOs de transporte, y no
tiene ninguna rama `if (v1) ... else (v2) ...`. Persiste en `domain_study_runs`:

- `multiplanar_run_id = canonical.multiplanarRunId()`
- `trace_id`, `requested_inference_mode`, `effective_inference_mode`
- `sagittal_model_key`/`axial_model_key` (de `plane.model().get("key")` — wire v2 —,
  con fallback a `plane.model().get("modelKey")` para runs adaptados desde v1)
- `sagittal_artifact_hash`/`axial_artifact_hash` (de `plane.model().get("artifactHash")`)
- `sagittal_run_id`/`axial_run_id` (= `plane.planeRunId()`, nunca `multiplanarRunId`)
- `status = "completed"`, o `"completed_synthetic"` si `canonical.synthetic()==true`
  (nunca se persiste una corrida sintetica con el mismo status que una real)
- `review_status = "pending"`

No lee `planes.sagital` (alias legacy), `aiOutput`, `modelArtifact` como fuente unica,
`measurements.values` ni metadata libre — esos conceptos no existen en el modelo
canonico.

### Snapshot de metricas versionado

Ejemplo real (tomado de la evidencia sanitizada 101_t2.mha, ver
`src/test/resources/contracts/ai-module-multiplanar-v2-real-baseline.json`):

```json
{
  "schemaVersion": "pfi.backend-run-snapshot.v2",
  "sourceSchemaVersion": "pfi.multiplanar-run.v2",
  "workspaceMode": "sagittal_only",
  "synthetic": false,
  "readiness": { "sagittal": true, "axial": false, "dual": false },
  "governance": {
    "humanReviewRequired": true,
    "notClinicalDiagnosis": true,
    "deidentified": true,
    "diagnosisGenerated": false
  },
  "threeD": {
    "enabled": false,
    "status": "blocked_missing_axial",
    "sourcePlaneRunIds": { "sagittal": "bec20aa91f96c9cd", "axial": null },
    "requiredInputs": ["axial_masks", "spacing", "slice_index_mapping"]
  },
  "planes": {
    "sagittal": {
      "model": {
        "key": "sagittal_spider",
        "version": "sagittal-spider-final-v1",
        "artifactHash": "cf11dcc0ad77a7c787e64a796a2fd7398ef906add461cef4b3d61f1a5238e944",
        "baselineReady": true,
        "availableForRealInference": true,
        "manifestValid": true
      },
      "input": {
        "inputId": "inp_2822bf9640ab40a289050a30ce2fe6fd",
        "nativeShape": [352, 384, 17],
        "canonicalShape": [352, 384, 17],
        "selectedSliceIndex": 7
      },
      "coordinateSpace": { "name": "model_256x256", "width": 256, "height": 256 },
      "series": [{ "id": "series-sag-t2", "plane": "sagittal", "selectedSliceIndex": 7, "sliceCount": 17, "status": "served_single_slice" }],
      "masks": [
        { "id": "mask-sagittal-canal", "classKey": "canal", "confidence": 0.9774 }
      ],
      "landmarks": [
        { "id": "lm-mask-sagittal-canal-centroid", "labelKey": "canal centroid", "x": 140.0, "y": 149.8, "confidence": null, "source": "derived_from_mask" }
      ],
      "measurements": [
        {
          "id": "sagittal-canal-area",
          "labelKey": "canal area",
          "aiValue": 1343.39,
          "value": 1343.39,
          "unit": "mm2",
          "confidence": 0.9774,
          "source": "AI",
          "status": "pending_review",
          "plane": "sagittal",
          "level": null,
          "measurementBasis": "physical_spacing",
          "linkedLandmarkIds": ["lm-mask-sagittal-canal-centroid"]
        }
      ],
      "quality": { "maskCount": 3, "landmarkCount": 3, "measurementCount": 9, "meanConfidence": 0.9869 }
    },
    "axial": null
  }
}
```

Notese que `landmarks` **no** tiene campo `z` — el contrato real (`PlaneLandmarkV2` en
`multiplanar_v2_models.py`) solo define `x`/`y`; no se inventa una tercera coordenada.

No incluye paths locales, URLs Cloudflare, tokens, metadata de sujeto ni bytes de
assets — solo la data de IA ya de-identificada por el propio contrato v2.

`StudyWorklistService` y `PatientHistoryService` leen este snapshot desde
`planes.<plane>.measurements` (lista canonica directa, ya no `measurements.values`
anidado del shape v1) — ambos se actualizaron junto con la persistencia para no
mostrar mediciones vacias tras la migracion.

### Assets

El AI Module v2 entrega los assets como **lista**, no mapa:

```json
{
  "assetName": "input.png",
  "role": "input_preview",
  "contentType": "image/png",
  "generated": true,
  "relativePath": "/assets/{planeRunId}/sagittal/input.png"
}
```

`MultiplanarRunPersistenceService` valida cada asset antes de crear su fila
`RunArtifact`: `generated=true`, `relativePath` sin `..`, sin esquema de URL externo
(`scheme://`), sin `localhost`/`cloudflare`/`host.docker.internal`, y que contenga el
`planeRunId` del plano al que dice pertenecer. Los assets invalidos simplemente no
generan fila (no rompen la persistencia del resto de la corrida).

Se crea una fila `RunArtifact` por cada asset valido — incluyendo `mask.npy` y
`confidence.npy` (metadata tecnica, para trazabilidad) — pero **solo** `input.png`,
`overlay.png` y `mask-preview.png` (cuando existe) son candidatos a snapshot BYTEA en
PostgreSQL via `RunAssetSnapshotService`; `.npy` nunca se trata como PNG ni se
descarga a BYTEA, aunque su fila de metadata sí quede persistida. El proxy publico
sigue siendo `GET /api/ai/assets/{planeRunId}/{plane}/{assetName}`.

### Mediciones

La lista de mediciones v2 se transforma a la estructura que consumen worklist,
historial y frontend P8: `id`, `labelKey`, `aiValue`, `value` (= `aiValue` al momento
de la corrida), `unit`, `confidence`, `source="AI"`, `status`, `plane`, `level`
(siempre `null` — nunca inventado), `measurementBasis`, `linkedLandmarkIds`. No se
inventan valores clinicos (`"L4-L5"`), `reviewerValue`, `outlier` ni landmarks. Las
correcciones del revisor siguen viviendo exclusivamente en `domain_review_corrections`,
sin tocar el snapshot de IA.

## 8. Diagnostico

`GET /api/system/diagnostics` (rol `ADMIN`) agrega bajo `aiModule`:

```json
{
  "multiplanarContractVersion": "v2",
  "multiplanarEndpoint": "/v2/multiplanar/run",
  "multiplanarSchemaExpected": "pfi.multiplanar-run.v2"
}
```

Nunca incluye `PFI_AI_SERVICE_URL` (la URL base del AI Module se considera
informacion de infraestructura potencialmente sensible).

## 9. Rollout seguro

**Fase 1** — desplegar este codigo con el default (`v1` implicito, sin tocar
variables): confirmar `GET /api/system/diagnostics` (`multiplanarContractVersion=v1`)
y que el pipeline P8 sigue funcionando sin cambios.

**Fase 2** — en Railway, configurar:

```
PFI_AI_SERVICE_MULTIPLANAR_CONTRACT_VERSION=v2
```

Redeploy sin tocar `PFI_AI_SERVICE_URL`. Ejecutar un caso nuevo real y verificar:
persistencia (`domain_study_runs` con `sourceSchemaVersion=pfi.multiplanar-run.v2`),
assets (BYTEA de `input.png`/`overlay.png`) y flujo de revision.

**Rollback** — revertir la misma variable:

```
PFI_AI_SERVICE_MULTIPLANAR_CONTRACT_VERSION=v1
```

No requiere revertir el commit ni tocar la base de datos: las corridas ya
persistidas (de cualquier version) quedan intactas, y el backend vuelve a llamar
`/multiplanar/run` en la siguiente request.

## 10. Compatibilidad P8

`POST /api/ai/multiplanar/run` sigue devolviendo el contrato publico
`multiplanar-run-v1` sin cambios de shape, sin importar que contrato se use
internamente contra el AI Module. `GET /api/studies`, `GET /api/studies/{caseId}`,
`GET /api/studies/{caseId}/runs`, `GET /api/subjects/{subjectRef}/history`,
`PUT /api/ai/runs/{runId}/review` y el proxy de assets no cambiaron. Vercel no
requiere ningun cambio para este release.

## 11. Plan P9-C

P9-B mantiene un puente de compatibilidad deliberado
(`CanonicalMultiplanarRunLegacyPresenter`) para que el frontend siga recibiendo el
shape `multiplanar-run-v1`. P9-C debera:

- migrar el contrato publico de `/api/ai/multiplanar/run` (o un endpoint nuevo) para
  exponer directamente el modelo canonico al frontend;
- retirar `CanonicalMultiplanarRunLegacyPresenter` y el adapter v1 una vez el
  frontend consuma canonico nativamente;
- evaluar si `MultiplanarRunResponsePresenter` (sanitizacion/URLs) se reescribe
  contra el modelo canonico en lugar del DTO legacy.

## 12. Limitacion axial

Este release no valida el contenido interno del plano axial en modo estricto v2 mas
alla de su presencia/ausencia acorde a lo solicitado — no hay una politica de
artifact congelado para axial (a diferencia de `sagittal_spider`), y el backend no
promueve ni altera el `trainingStatus` de ningun artifact axial. Si el AI Module
responde `MODEL_NOT_READY` para axial, el error se propaga sin persistir nada
parcial; una validacion estricta de axial equivalente a la de sagital queda fuera de
alcance de P9-B.
