# Backend — cambios para la estación de lectura

Fecha: 2026-07-30/31. Rama: `feat/study-zip-ingestion`.

Dos ejes: dejar pasar datos clínicos que el backend estaba descartando, y persistir
las anotaciones del revisor, que hasta ahora se perdían al cerrar la pantalla.

---

## 1. Campos que se descartaban en silencio

El backend filtra lo que no declara. Tres campos que el AI Module ya emitía nunca
llegaban al frontend porque no estaban en el DTO correspondiente.

### `measurement.level` — se pisaba con `null` a propósito

**Archivo:** `service/MultiplanarRunPersistenceService.java`

`transformMeasurements` tenía `transformed.put("level", null)` fijo. Era correcto
mientras el AI Module solo mandaba el nombre del plano ("sagittal") en ese campo:
dejarlo pasar habría archivado cada medición bajo un nivel que no existe.

Ahora el AI Module emite niveles lumbares reales, así que el campo se propaga —
**pero solo si es un nivel lumbar de verdad**. `lumbarLevel()` acepta únicamente
L1-L2, L2-L3, L3-L4, L4-L5 y L5-S1; cualquier otra cosa (incluido el nombre del
plano, que es lo que mandan las corridas viejas) se descarta y la medición queda sin
nivel, agrupada aparte, en vez de misfiled.

### `measurement.sliceIndex` y `quality.slicePreviewCount`

**Archivos:** `dto/AiPlaneMeasurementV2Dto.java`, `dto/AiPlaneQualityV2Dto.java`

Ninguno estaba declarado. Detalle que cuesta ver: aunque `AiPlaneQualityV2Dto` tiene
`@JsonIgnoreProperties(ignoreUnknown = false)`, el campo desconocido se descartaba en
silencio en vez de fallar, porque esa anotación con `false` defiere al
`FAIL_ON_UNKNOWN_PROPERTIES` del ObjectMapper, que está apagado. El síntoma era que
el dato existía en la respuesta del AI Module y no aparecía en la base.

`slicePreviewCount` es lo que permite al visor distinguir "este corte no tiene
imagen" de "sí la tiene" sin pedir el PNG y tratar el 404 como respuesta.

---

## 2. Assets por corte

**Archivo:** `service/AiBackendService.java`

`validateAssetRequest` tenía una lista blanca fija (`input.png`, `overlay.png`,
`mask-preview.png`, `lumbar-3d-mesh.json`). Las previsualizaciones por corte no
pueden estar en una lista fija porque cuántas hay depende del estudio.

Se agregó `isSlicePreviewName()`, con el patrón `^slice-\d{3,5}\.png$`. Es tan
estricto como la lista —solo dígitos, extensión fija, sin separadores de ruta— así
que no amplía la superficie de path traversal que la lista ya cerraba. Los chequeos
que restringen el plano `workspace` a la malla 3D siguen intactos.

---

## 3. El validador de contrato congelado, actualizado a propósito

**Archivo:** `service/MultiplanarV2RealBaselineValidator.java`

`validateFrozenSagittalSpiderArtifactCounts` exigía **exactamente 9 mediciones** para
la release `sagittal_spider/sagittal-spider-final-v1`. Era una aserción
point-in-time: 3 clases × área/ancho/alto.

El AI Module ahora parte la clase `disc_group` en sus componentes conexas, así que
cada disco aporta sus tres magnitudes y el total depende del campo de visión del
estudio. El guard se cambió deliberadamente:

- **piso** de 9 mediciones (`>= FROZEN_MEASUREMENT_COUNT`), y
- **múltiplo de 3** (`% MAGNITUDES_PER_STRUCTURE == 0`), que es la invariante que
  sigue valiendo: cada estructura aporta exactamente sus tres magnitudes.

Las cuentas de máscaras (3) y landmarks (3) no cambian.

Esto es drift de contrato intencional. El guard existía justamente para obligar a
confrontarlo, y se documentó en el propio Javadoc del método.

---

## 4. Anotaciones del revisor — persistencia nueva

Antes, todo lo que el revisor marcaba sobre la imagen se perdía al cerrar la
pantalla.

### Tabla propia

**Archivo:** `docs/migrations/V20260730_013_reviewer_annotations.sql`

`domain_reviewer_annotations`, no dentro de `domain_review_corrections`. Una
corrección es siempre un valor de la IA que el revisor cambia (`measurement_id`,
`before_value`, `after_value`); una anotación es geometría y texto propios que pueden
no corresponder a ninguna medición. Guardarlas juntas dejaría media fila vacía en
cada caso e inventaría un `measurement_id` inexistente.

El alcance (`scope`) decide dónde se dibuja la anotación, así que los invariantes que
la hacen ubicable están en `CHECK`:

- `scope` ∈ {study, level, slice}; `kind` ∈ {measurement, marker, note}
- `scope = 'slice'` exige `plane` y `slice_index`
- `scope = 'level'` exige `level`
- `unit` ∈ {mm, px} — nunca mm si la corrida no informó escala física

`points` va en JSONB, en la base normalizada 0..256 que comparten máscaras y
landmarks: un espacio relativo al marco, no píxeles del PNG, así que la geometría
sigue alineada a cualquier resolución.

### Dominio, servicio y endpoints

- `domain/ReviewerAnnotation.java` — repite los mismos invariantes que los `CHECK`.
  No es redundancia: el repositorio en memoria, que usan las pruebas y el modo demo,
  tiene que rechazar exactamente lo mismo que rechazaría Postgres.
- `service/ReviewerAnnotationService.java` — un payload que no se puede ubicar en una
  imagen se rechaza con 400, no se fuerza a algo almacenable. Acepta ids no-UUID y
  acuña uno: la sala de lectura crea anotaciones localmente (`measure-<timestamp>`) y
  solo persiste al guardar, así que el primer viaje legítimamente lleva un id local.
- `controller/AiRunReviewController.java` — `GET` y `PUT`
  `/api/ai/runs/{multiplanarRunId}/annotations`, con el mismo `requireProfessional`
  que la revisión.

**El PUT reemplaza el conjunto completo.** La sala de lectura agrega y borra en
pantalla y guarda como unidad; un endpoint de solo-agregar dejaría el borrado sin
forma de expresarse.

### Repositorio

`StudyRepository` gana `replaceAnnotations` y `findAnnotationsByRunId`, implementados
en la versión en memoria y en la de Postgres (esta última en una transacción:
borra + inserta, con rollback).

---

## Verificación

- **Backend: 448 de 458 pruebas.** Los 10 fallos son todos el mismo
  `IllegalStateException: Could not find a valid Docker environment` de Testcontainers,
  que no alcanza el socket de Docker desde el shell usado. Verificado contando los
  reportes de surefire con ese error: exactamente 10. No hay fallos de código.
- **`ReviewerAnnotationTest`** (7 casos) cubre los invariantes del dominio y sí corre.
- **End-to-end contra el stack levantado**: medición de 38.3 mm creada en la sala de
  lectura, guardada con la revisión, recuperada por `GET /annotations` y vuelta a
  aparecer tras recargar la página entera.
- Ampliar `StudyRepository` y el constructor del controlador rompió 6 dobles de
  prueba. Se actualizaron respetando su intención: los `BrokenStudyRepository` siguen
  lanzando `UnsupportedOperationException` en todo, y los tests que mockean sus
  colaboradores reciben un mock del servicio nuevo.

---

## Lo que sigue pendiente

- Propagar `origin` y `direction` de paciente desde el AI Module para habilitar la
  línea de referencia entre planos.
- Servir máscaras por clase, hoy compuestas en un solo PNG.
