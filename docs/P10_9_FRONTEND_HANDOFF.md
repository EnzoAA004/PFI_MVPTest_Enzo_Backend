# P10.9 Frontend Handoff

Backend + AI Module product integration checkpoint for Francisco (Frontend). This
document describes only what actually runs end to end today, verified against a real
DICOM study through `scripts/run_p10_9_backend_ai_e2e.ps1`. Fixtures under
`docs/fixtures/p10-9/` are trimmed copies of the real responses captured by that run, not
hand-written examples.

Every route below is served by the Backend. Frontend never calls the AI Module directly.

## 0. Capability status — read this before building anything

- **P10.7 (disc degenerative findings): available.** Real end-to-end path (sagittal T1 +
  T2 full-series segmentation -> `disc-degenerative-findings` -> PostgreSQL persistence)
  verified against a real DICOM study. 8 finding types, see §7 for which are
  `supported_internal` vs `experimental` vs `not_product_supported`.
- **Automatic disc localization: technically validated on a real-study fixture, not
  clinically validated.** `automaticDiscLocalizationRealStudyValidated = true` means the
  segmentation -> level -> bbox -> P10.7 chain ran with zero manual coordinates and zero
  dataset ground truth on this one real study. `automaticDiscLocalizationValidated`
  (clinical/general accuracy) stays `false`. Do not present the first as proof of the
  second in any UI copy.
- **P10.6 (subarticular stenosis): capability BLOCKED. No automatic ROI.** The
  `axial_t2_alkafri` model has no vertebra class, so there is no automatic,
  code-verifiable way to assign an axial slice to a lumbar level without either
  sagittal-to-axial registration (not validated) or dataset ground truth (not usable in
  product). **Frontend must not offer P10.6 as an automatic, one-click feature.** The
  only existing route, `POST /api/ai/degenerative-findings/subarticular`, requires a
  professional to type in `inputId, instanceNumber, x, y, side, level` by hand and its
  result is `researchOnly` — audited, never persisted as a clinical finding. If you
  expose it at all, label it as a manual research tool, not a product feature.
- **All findings, from any of the above, require professional review before they inform
  any decision.** `humanReviewRequired: true` / `notClinicalDiagnosis: true` /
  `autonomousDiagnosis: false` are present on every response in this document — treat
  their absence as a contract violation, not a value to ignore.

## 1. Flow Frontend should implement

```
login (existing auth)
  -> POST /api/ai/studies (upload ZIP)                         -> caseId, studyId, per-plane inputIds
  -> GET  /api/studies/{caseId}                                 -> study metadata / series list
  -> POST /api/ai/multiplanar/run                                -> persisted run (multiplanarRunId), sagittal+axial segmentation, masks, measurements
  -> [optional] POST /api/ai/v2/product/series-segmentation      -> full-series (every slice) sagittal/axial segmentation, per plane
  -> [optional] POST /api/ai/v2/product/disc-degenerative-findings -> P10.7 findings, only after two series-segmentation calls (T1 and T2)
  -> image viewer: GET /api/ai/v2/product/series-segmentation/{runId}/{plane}/slices/{index}/original.png (and overlay.png on demand)
  -> findings + measurements panel (read from the run responses above)
  -> GET  /api/ai/runs/{multiplanarRunId}/review                 -> current review state
  -> PUT  /api/ai/runs/{multiplanarRunId}/review                 -> save professional correction/review
  -> GET  /api/studies/{caseId}/runs                              -> re-fetch persisted state (confirms durability, e.g. after navigating away)
```

`POST /api/ai/multiplanar/run` is the one call that persists a reviewable PostgreSQL run
and is required before review or P10.7 findings can be saved. The `/api/ai/v2/product/*`
routes are additive on top of it, not a replacement.

## 2. Endpoint table

| Method | Route | Auth | Request | Response | When Frontend uses it | Expected errors |
|---|---|---|---|---|---|---|
| POST | `/api/ai/studies` | Bearer, approved professional/admin | multipart: `file` (.zip), `caseId` | `study-upload-response.json` shape | After the user picks a study ZIP to upload | 400 invalid file/caseId, 413 too large, 422 no usable planes |
| GET | `/api/studies/{caseId}` | Bearer | — | study detail | Study worklist / detail view | 404 unknown caseId |
| GET | `/api/studies/{caseId}/runs` | Bearer | — | `{ runs: [{ summary, measurementsByPlane, artifactsByPlane, corrections, metricsSnapshot }] }` | Re-fetching persisted state, confirming durability | 404 unknown caseId |
| POST | `/api/ai/multiplanar/run` | Bearer | `{ caseId, sagittalInputId, axialInputId, sagittalModelKey: "sagittal_spider", axialModelKey: "axial_t2_alkafri", allowContractFallback: false }` | `product-analysis-response.json` shape (`runId`, `planes.sagittal`, `planes.axial`, each with `masks`, `findings`, `landmarks`, `measurements`, `assets`) | After the user confirms which sagittal/axial input to analyze | 400 invalid input, 502/504 AI unavailable/timeout |
| POST | `/api/ai/v2/product/series-segmentation` | Bearer | `{ caseId, inputId, plane, modelKey }` | `series-segmentation-response.json` shape (`slices[]`, one per DICOM slice, each with `assets.original`/`assets.overlay`, `measurements`, `discLocalizations` for sagittal) | Full-series review mode (every slice, not just the representative one) | 400/404/409/422 depending on input state, 502 upstream |
| GET | `/api/ai/v2/product/series-segmentation/{runId}/{plane}/slices/{index}/{original\|overlay}.png` | Bearer | — | `image/png` | Rendering a slice in the viewer | 400 invalid runId/plane/index, 404 unknown asset |
| POST | `/api/ai/v2/product/disc-degenerative-findings` | Bearer | `{ multiplanarRunId, caseId, sources: [{ role: "sagittal_t1"\|"sagittal_t2", inputId, segmentationRunId }] }` (1 or 2 sources; T1 and T2 are independent series-segmentation calls) | `degenerative-findings-response.json` shape | After both T1 and T2 full-series segmentation succeeded | 400 malformed sources, 409 run not found, 502 invalid upstream contract |
| GET / POST / PUT | `/api/ai/runs/{multiplanarRunId}/review` | Bearer, professional | `{ reviewStatus, reviewer, comments, corrections: [{ measurementId, label, beforeValue, afterValue, comment }] }` | `review-response.json` shape | Saving/reading a professional's review | 400 invalid body, 403 not authorized for this run |
| GET / PUT | `/api/ai/runs/{multiplanarRunId}/annotations` | Bearer, professional | list of annotations | list of annotations | Freehand reviewer annotations, separate from measurement corrections | 400/403 |

Every error response follows `error-response.json`'s shape: `status`, `code`, `message`,
`traceId`, `path`, `method`, `category`, `retryable`, `humanReviewRequired`,
`notClinicalDiagnosis`. Never render `message` as if it were a diagnosis — it is always a
generic, Backend-authored string.

## 3. IDs — what they mean

- **caseId**: pseudonymous case identifier the professional chooses at upload time (e.g. `"P10-9-REAL-STUDY"`). Not a patient identifier.
- **studyId**: opaque ID minted by the AI Module for one upload transaction.
- **inputId**: opaque ID for one registered series (one per analyzable series: `sagittal`, `axial`, and, when both weightings exist, `sagittalT1`/`sagittalT2` too). `sagittal` is whichever weighting the system prefers (T2 first) for the single-plane `/api/ai/multiplanar/run` contract; `sagittalT1`/`sagittalT2` are the same series exposed explicitly by weighting, for P10.7's full-series routes.
- **series id (public/opaque)**: `seriesFound[].inputId` in the study upload response — every classified series gets one, even series the AI never runs on (T1 axial, localizers, etc.), so the reader can still show them. `seriesInstanceUid` never leaves the AI Module.
- **segmentationRunId**: returned by `POST /api/ai/v2/product/series-segmentation` (field `runId` in that response). Scoped to one plane/modelKey/input combination.
- **persisted runId (multiplanarRunId)**: returned by `POST /api/ai/multiplanar/run` (field `runId`). This is the PostgreSQL-durable identifier — review, P10.7 persistence, and `GET /api/studies/{caseId}/runs` all key off this one, not off `segmentationRunId`.
- **review id**: there is no separate review id; review is addressed by `multiplanarRunId`.

## 4. States

- Study upload: `200` with `sagittal`/`axial` (and `sagittalT1`/`sagittalT2` when both weightings exist) present, or `warnings[]` explaining what is missing. No separate "processing" state — study upload is synchronous.
- Multiplanar run / series-segmentation: synchronous, `status: "completed"`, `coverageComplete: true` when every DICOM slice was segmented. No polling needed today.
- Review: `reviewStatus` values used by `RunReviewService` today include `"observed"` (what this E2E used) — check with the current `RunReviewRequestDto`/`RunReviewResponseDto` for the authoritative enum before hardcoding a list, since it is validated Backend-side, not enumerated in a shared contract file yet.
- Failure: any 4xx/5xx from the table above, always shaped like `error-response.json`.

## 5. Sagittal / axial — which series for what

- **Sagittal T1** (`sagittalT1`): shows fat and marrow; needed to read the study fully, not run through P10.7 alone.
- **Sagittal T2 / STIR** (`sagittalT2`, also the default `sagittal`): primary sagittal series for segmentation and disc localization.
- **Axial T2** (`axial`): the only axial series the product runs a model on today; axial T1 is registered and viewable but has no model.
- **Do not assume pixel registration between any of these.** T1 and T2 sagittal are independent series, never registered to each other. There is no automatic sagittal-to-axial alignment (`automaticSagittalAxialAlignmentValidated` stays `false`) — do not draw an axial finding on the sagittal image or vice versa based on slice index alone.

## 6. Viewer

- Show **original** by default (`assets.original` / `.../original.png`).
- Show **overlay** only on demand (`assets.overlay` / `.../overlay.png`), never by default.
- Overlay colors encode **anatomy** (`semanticColors` map: disc=blue, vertebra=amber, canal=green, posterior element=orange), never severity. Do not recolor by finding grade.

## 7. Findings

**P10.7** (`disc-degenerative-findings`) returns exactly these 8 finding types — do not add
or infer others:

| findingType | deploymentStatus today |
|---|---|
| `upper_endplate_change` | `supported_internal` |
| `lower_endplate_change` | `supported_internal` |
| `disc_narrowing` | `supported_internal` |
| `disc_bulging` | `supported_internal` |
| `pfirrmann_grade` | `experimental` |
| `modic_change` | `not_product_supported` |
| `spondylolisthesis` | `not_product_supported` |
| `disc_herniation` | `not_product_supported` |

Read `finding.evidence.deploymentStatus` per finding and gate the UI on it: show
`supported_internal` normally, visibly flag `experimental`, and either hide or clearly
badge `not_product_supported` findings — do not present all 8 as equally reliable.

**P10.6** (subarticular): `automaticRoiAvailable = false` today. `POST
/api/ai/degenerative-findings/subarticular` exists but requires a professional to supply
`inputId, instanceNumber, x, y, side, level` manually — there is no automatic ROI, and its
result is `researchOnly` (audited, not persisted as a clinical finding). Do not build a UI
that implies P10.6 runs automatically; if you expose it, it must be an explicit
manual-coordinate tool for research use.

All findings: `humanReviewRequired: true`, `notClinicalDiagnosis: true`,
`autonomousDiagnosis: false` are present on every response — surface these, do not strip
them in the UI layer.

## 8. Measurements

Shape (see `measurements-response.json`): `planes.{sagittal|axial}.measurements.values[]`,
each `{ id, labelKey, value, unit, confidence, source: "ai", status: "pending_review", plane, level, sliceIndex, points }`.
`source` is always `"ai"` until a professional corrects it — no separate "human" value
appears inline; corrections live in the review's `corrections[]` list (see below) and the
run's `corrections` field from `GET /api/studies/{caseId}/runs`.
Units and values are descriptive only (distance/area/angle) — never render a
normal/mild/moderate/severe label unless it comes from an explicit finding classification
with a supported `deploymentStatus`.

Correction endpoint: `PUT /api/ai/runs/{multiplanarRunId}/review` with
`corrections: [{ measurementId, label, beforeValue, afterValue, comment }]`, where
`measurementId` is the `id` from the measurement above.

## 9. Review

`GET /api/ai/runs/{multiplanarRunId}/review` returns the current review state.
`POST`/`PUT` on the same route saves it. Fields verified in this E2E:
`reviewStatus`, `reviewer`. The persisted run's `corrections[]` (from
`GET /api/studies/{caseId}/runs`) is the durable list of what was corrected and by whom —
that is the source of truth for "AI said X, professional changed it to Y", not the
in-memory review response alone.

## 10. Assets

Always fetch images through Backend-relative URLs
(`/api/ai/v2/product/series-segmentation/...` or the legacy `/api/ai/assets/...` from
`/api/ai/multiplanar/run`). Never construct a URL pointing at the AI Module directly —
Frontend has no AI Module base URL and should not need one.

## 11. Error handling

Handle at least: `401` (session expired, prompt re-login), `403` (not authorized for this
run/professional not approved), `400` (validation — show the generic `message`), `404`
(unknown caseId/run/asset), `409` (state conflict, e.g. re-submitting an immutable P10.7
prediction), `413` (file too large), `502`/`504` (AI Module unavailable/slow — show a
retry, not an error implying the analysis failed for clinical reasons).

## 12. Clinical safety

This is a professional support tool. Every AI output requires human review before it
informs any clinical decision. Nothing produced by this system is a diagnosis. Always
keep `humanReviewRequired`/`notClinicalDiagnosis` visible near AI-generated content;
never present a finding, measurement, or checkpoint result as a standalone conclusion.

## Frozen contract table

Contract shape is frozen where marked YES: Frontend can build against the response as
documented above without expecting the JSON shape itself to change. "Frozen" does not
mean the values behave a certain way clinically — it means the fields, types, and
nesting are stable.

| Area | Contract frozen |
|---|---|
| Auth | YES (pre-existing, unchanged by P10.9) |
| Study upload | YES (`study-upload-response.json`, incl. `sagittalT1`/`sagittalT2`) |
| Series selection | YES (`seriesFound[]` in study upload response) |
| Sagittal viewer | YES (`product-analysis-response.json` / `series-segmentation-response.json` asset URLs) |
| Axial viewer | YES (same as sagittal) |
| Assets | YES (Backend-relative PNG URLs) |
| P10.7 findings | YES (`degenerative-findings-response.json`, 8-type taxonomy + deploymentStatus) |
| P10.6 findings | BLOCKED (no automatic ROI; manual-coordinate route exists but is research-only, not a frozen product contract) |
| Measurements | YES (`measurements-response.json` shape) |
| Review | YES (`review-response.json` shape) |
| Error model | YES (`error-response.json` shape, pre-existing `ApiErrorWriter` contract) |
