# P10.5-C - Persistencia y serving del catalogo volumetrico

## Estado

P10.5-C deja al Backend preparado para consumir el catalogo volumetrico producido por el AI Module P10.5-B, persistirlo dentro del snapshot canonico de la corrida y reabrirlo desde PostgreSQL sin depender del AI Module.

## Decision de esquema

No se agrega migracion SQL. El esquema actual alcanza:

- `domain_study_runs.metrics_snapshot` conserva el catalogo canonico completo.
- `domain_run_artifacts` ya tiene una fila por asset derivado y una restriccion unica por corrida, plano y `asset_name`.
- `domain_run_asset_payloads` ya almacena el payload durable de PNG/JSON por `artifact_id`.

## Representacion canonica

El Backend normaliza estas variantes hacia `plane.input.slices`:

- contrato v2: `plane.input.slices`;
- contrato legacy: `plane.metadata.slices`.

Cada slice conserva:

- `index` 0-based;
- `displayIndex` 1-based;
- `previewAsset`;
- `overlayAsset` solo si `hasResults=true`;
- `measurementIds`;
- `landmarkIds`.

La respuesta publicada al frontend usa URLs del Backend:

```json
{
  "index": 8,
  "displayIndex": 9,
  "hasResults": true,
  "previewAsset": {
    "assetName": "slice-008.png",
    "role": "slice-preview",
    "contentType": "image/png",
    "generated": true,
    "url": "/api/ai/assets/run-sag-123/sagittal/slice-008.png",
    "storageStatus": "stored",
    "available": true
  },
  "overlayAsset": {
    "assetName": "slice-008-overlay.png",
    "role": "slice-overlay",
    "contentType": "image/png",
    "generated": true,
    "url": "/api/ai/assets/run-sag-123/sagittal/slice-008-overlay.png"
  }
}
```

No se publica `relativePath`, `sourcePath`, storage keys, paths locales ni hosts internos.

## Allow-list de assets

Assets publicos permitidos:

- `input.png`
- `overlay.png`
- `mask-preview.png`
- `lumbar-3d-mesh.json`
- `slice-###.png`
- `slice-###-overlay.png`

Los patrones de slice son exactos y anclados:

- `^slice-\d{3}\.png$`
- `^slice-\d{3}-overlay\.png$`

Se rechazan barras, backslashes, `..`, URL schemes, drive letters, content-type incorrecto y nombres fuera del patron.

## Persistencia y snapshot durable

Durante la persistencia de una corrida se registran `RunArtifact` para:

- assets legacy validos;
- mesh experimental `lumbar-3d-mesh.json`;
- previews `slice-###.png`;
- overlay `slice-###-overlay.png` del slice inferido.

El snapshot descarga cada asset permitido desde el AI Module y valida:

- estado HTTP 2xx;
- `image/png` para previews/overlays;
- `application/json` para mesh;
- payload no vacio;
- firma PNG;
- tamano maximo;
- SHA-256.

Estados:

- `stored`: payload valido guardado en PostgreSQL;
- `missing`: upstream devolvio 404;
- `rejected`: nombre, contrato, content-type, payload o tamano invalidos.

## Preview faltante

Un preview faltante no invalida automaticamente toda la corrida. El slice queda con `storageStatus=missing` y `available=false`; la corrida sigue siendo reabrible y revisable con estado parcial/degradado para ese asset. No se fabrica contenido.

## Serving autorizado

El endpoint existente sirve por Backend:

```text
GET /api/ai/assets/{runId}/{plane}/{assetName}
```

Para assets persistidos busca primero el payload durable en PostgreSQL. Si esta disponible responde `200` con el `content-type` esperado. Si el asset esta `missing`, `rejected` o no tiene payload, responde `404` seguro mediante el contrato uniforme de errores. La autorizacion existente del backend se conserva para proteger el acceso por estudio/recurso.

## Compatibilidad legacy

Las corridas sin `slices[]` se mantienen compatibles: no se inventa catalogo volumetrico y no se agrega `volumeCatalogStatus`. Los assets legacy `input.png`, `overlay.png`, `mask-preview.png` y el mesh experimental siguen funcionando.

## Pruebas

Cobertura agregada o extendida:

- adaptacion v2 de catalogo de 17 slices;
- adaptacion legacy desde `metadata.slices`;
- normalizacion de indices, orden, displayIndex y URLs backend;
- validacion de nombres, traversal, URL absoluta, barra/backslash y content-type;
- persistencia de previews/overlay como `RunArtifact` sin duplicados;
- snapshot durable con SHA-256 y tamano;
- estados `stored`, `missing`, `rejected`;
- serving desde PostgreSQL con AI Module apagado;
- 404 seguro para asset no disponible;
- regresion de assets legacy y mesh experimental.

## Limitaciones

P10.5-C no cambia el contrato del AI Module ni agrega reconstruccion volumetrica. La calidad y completitud geometrica siguen viniendo del productor P10.5-B/P10.5-E. La persistencia de correcciones profesionales y mediciones existentes se conserva, pero no se versiona especificamente por slice en este ticket.

## Reproduccion

Desde una ruta corta fuera de OneDrive:

```powershell
mvn.cmd -q -DskipTests compile
mvn.cmd -q -Dtest=VolumeSliceCatalogServiceTest test
mvn.cmd -q -Dtest=AiMultiplanarV2ResponseAdapterTest,AiMultiplanarV1ResponseAdapterTest test
mvn.cmd -q -Dtest=RunAssetSnapshotServiceTest test
mvn.cmd -q -Dtest=AiAssetDurableProxyControllerTest test
mvn.cmd -q -Dtest=MultiplanarRunPersistenceServiceTest test
mvn.cmd -q test
```
