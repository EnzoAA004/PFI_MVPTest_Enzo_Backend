# P10.5-E-BE - Asociacion persistente de resultados y revision por slice

## Estado

P10.5-E-BE completa la asociacion entre el catalogo volumetrico persistido en P10.5-C y los resultados automaticos por slice. El backend conserva indices 0-based, `displayIndex` 1-based, assets servidos por `/api/ai/assets/...`, mediciones, landmarks y correcciones profesionales asociadas a `plane + sliceIndex + measurementId`.

## Brecha encontrada

P10.5-C ya persistia `plane.input.slices[]`, previews, overlays y estados durables de assets. Faltaba validar que `measurementIds` y `landmarkIds` apuntaran a resultados existentes del plano, evitar IDs duplicados o inexistentes, y reinyectar las correcciones profesionales en el slice correcto al reabrir una corrida.

## Decision de migracion

No se agrega migracion SQL. El esquema actual alcanza:

- `domain_study_runs.metrics_snapshot` conserva el catalogo canonico y resultados automaticos.
- `domain_review_corrections.before_value` y `after_value` son JSON y permiten conservar `plane`, `sliceIndex`, valor automatico y valor corregido.
- `domain_run_artifacts` y `domain_run_asset_payloads` siguen resolviendo previews, overlays y mesh sin blobs clinicos en tablas de dominio.

Si mas adelante se requiere versionado historico completo por slice, eso queda fuera de este ticket.

## Asociacion por slice

Cada entrada de `slices[]` queda asociada de forma estable con:

- `index` 0-based;
- `displayIndex` 1-based;
- `previewAsset`;
- `overlayAsset` solo si `hasResults=true` y el overlay es valido;
- `measurementIds` validados contra `planes.{plane}.measurements[].id`;
- `landmarkIds` validados contra `planes.{plane}.landmarks[].id|landmarkId|name`;
- `planeRunId`, `runId` y `plane` del plano.

Los slices sin resultados automaticos se publican con:

```json
{
  "hasResults": false,
  "measurementIds": [],
  "landmarkIds": [],
  "overlayAsset": null,
  "resultStatus": "no_automatic_results"
}
```

Cuando hay referencias inconsistentes, el slice se degrada de forma explicita con `resultStatus=degraded_inconsistent_result_references` y listas como `invalidMeasurementIds`, `duplicateMeasurementIds`, `invalidLandmarkIds` o `duplicateLandmarkIds`. No se fabrican IDs ni se mueven resultados entre slices.

## Revision profesional

La revision profesional reutiliza `domain_review_corrections`. Al guardar una correccion, el backend valida que `measurementId` exista en el snapshot del run y que, si se informa `plane` o `sliceIndex`, coincidan con la medicion y el catalogo del slice.

La correccion persistida conserva:

- `measurementId`;
- `plane`;
- `sliceIndex`;
- valor automatico en `beforeValue`;
- valor corregido en `afterValue`;
- `reviewer` y `reviewedAt` desde `domain_study_runs`;
- `comment`;
- estado de revision del run.

## Reapertura

La respuesta viva de `POST /api/ai/multiplanar/run` y la reapertura desde PostgreSQL usan la misma normalizacion de slice results. El backend normaliza el `CanonicalMultiplanarRun` recibido del AI Module antes de presentarlo y antes de persistirlo, por lo que `hasResults`, `resultStatus`, `measurementIds`, `landmarkIds`, referencias invalidas/duplicadas y `overlayAsset` no dependen del camino por el que se lea la corrida.

Al consultar el detalle de estudio/run, el backend publica el snapshot con el catalogo ordenado y vuelve a asociar correcciones al slice correcto. La reapertura funciona con AI Module apagado siempre que los assets durables esten disponibles en PostgreSQL. Si un preview esta `missing` o `rejected`, el slice queda visible como degradado y no se inventa contenido. Los metadatos durables de assets (`storageStatus`, `available`, `sha256`, `sizeBytes`) pueden aparecer solo en la reapertura, pero la semantica de resultados por slice debe coincidir con la respuesta viva.

## Seguridad

Las URLs publicas siguen apuntando solo al backend:

```text
/api/ai/assets/{runId}/{plane}/{assetName}
```

No se publican `relativePath`, `sourcePath`, storage keys, hosts internos, paths locales ni tokens. Las respuestas mantienen `humanReviewRequired=true` y `notClinicalDiagnosis=true` cuando el snapshot de gobierno lo confirma.

## Pruebas

Cobertura agregada o extendida:

- catalogo de 17 slices;
- resultados solo en el slice seleccionado;
- validacion de `measurementIds` y `landmarkIds`;
- degradacion de IDs inexistentes;
- deteccion de duplicados;
- overlay solo en slice con resultados;
- correccion profesional asociada a slice;
- reapertura con correccion persistida;
- reapertura sin paths internos;
- compatibilidad legacy sin `slices[]`;
- preview `missing` y `rejected` cubiertos por P10.5-C;
- autorizacion de review existente preservada.
- simetria entre respuesta viva del run y reapertura persistida para resultados por slice.

## Limitaciones

P10.5-E-BE no agrega versionado historico de cada correccion ni workflow clinico avanzado. Tampoco interpreta mediciones como diagnostico clinico. La geometria volumetrica final y evidencias 3D completas siguen dependiendo de P10.5-E/P10.5-F del AI Module y Frontend.

## Impacto para Francisco

El frontend puede seleccionar un slice y leer:

- `hasResults`;
- `resultStatus`;
- `measurementIds`;
- `landmarkIds`;
- `overlayAsset`;
- `corrections`;
- estado durable del `previewAsset`.

Esto permite mostrar "Sin resultados automaticos en este corte" sin consultar el AI Module, y mostrar mediciones/correcciones solo cuando pertenecen al slice seleccionado.

## Deuda de Testcontainers

Los tests PostgreSQL/Testcontainers siguen siendo el gate real para persistencia durable. Si Docker Desktop no esta visible para la sesion de Maven, los tests de integracion fallan antes de ejecutar assertions con `Could not find a valid Docker environment`; ese bloqueo debe reportarse sin simular resultados.
