# P10.5 — Nota de roadmap (visor volumétrico navegable)

Este repo no tenía un doc de roadmap propio (a diferencia del AI Module y el Frontend); esta nota lo suple para P10.5 hasta que se consolide en un lugar único.

**P10.5-A cerrado (2026-07-28)**: contrato volumétrico definido en `docs/P10_5_A_VOLUMETRIC_CONTRACT.md` (copia de la versión canónica en el AI Module) + 8 fixtures en `docs/fixtures/p10-5-a/`.

**Próximo bloque para este repo: P10.5-C** (después de que el AI Module cierre P10.5-B). Resumen de la auditoría ya hecha (detalle completo en la sección 1.2/8 del contrato):

- No hace falta migración de base de datos. `domain_run_artifacts` ya es `UNIQUE (study_run_id, plane, asset_name)` — un preview de slice es solo un `asset_name` más (`slice-008.png`), dentro de la misma restricción.
- `PostgresRunAssetContentStorage` no cambia — un preview PNG ya encaja en la rama `image/png` existente de `validatePayloadContract()`.
- `GET /api/ai/assets/{runId}/{plane}/{assetName}` (`AiBackendController.java:97`) no cambia de ruta — sirve cualquier asset registrado.
- Cambios reales: `dto/AiPlaneInputV2Dto.java` (agregar `seriesId`, `sourceFormat`, `originMm`, `directionMatrix`, `geometryComplete`, `slices` tipado — no `Map<String,Object>`), `MultiplanarRunPersistenceService.isAllowedAsset()`/`isValidAssetMetadata()` y `RunAssetSnapshotService.PUBLIC_ASSETS` (pasan de enum fijo a patrón `slice-\d{3}(-overlay)?\.png`).
- Confirmar (no asumido) que `MultiplanarRunResponsePresenter`/`CanonicalMultiplanarRunLegacyPresenter` no reenvían `sourcePath`/`outputFiles` si el AI Module llegara a emitirlos sin filtrar — riesgo real identificado en el contrato, no en este repo directamente.

No implementar todavía — bloqueado por P10.5-B (AI Module).
