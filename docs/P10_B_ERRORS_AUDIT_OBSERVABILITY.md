# P10-B — Unified Error Contract, Tracing, Sanitized Audit & Operational Observability

Commit base: `4e206398471ec8ce6091e3892f1a7a1bee3f472d` (P10-A.2.2)
Repository: `EnzoAA004/PFI_MVPTest_Enzo_Backend`

P10-A/P10-A.1/P10-A.2/P10-A.2.1/P10-A.2.2 closed authentication, authorization, and
account-state integrity. P10-B does not change any of that — it makes the backend's
*operational* surface (errors, logs, audit trail, metrics, diagnostics) consistent,
sanitized, and traceable end-to-end, in preparation for Railway today and Google Cloud
later.

## 0. P10-B.1 hotfix (base `40dfe7dac0045b9bc7e80d93e3354288ac250c27`)

A post-review pass on P10-B found four real problems, all closed in this pass — sections
below are updated in place to reflect the corrected behavior rather than kept as two
divergent documents:

1. **Some 5xx handlers still returned `ex.getMessage()`.** `ApiExceptionHandler`'s
   `AiContractViolationException`/`AiMultiplanarContractViolationException`/
   `AiMultiplanarUpstreamException`/`DatabaseUnavailableException` handlers used
   `safeMessage(ex.getMessage(), status)`, which fell through to the exception's own
   message whenever it was non-blank — and `AiServiceClient` was, at the time, populating
   those messages from the upstream response body/message. **Fixed**: every 5xx handler
   now uses a fixed message from `ApiErrorCode.publicMessage()` looked up by code, never
   `ex.getMessage()`; `ResponseStatusException` on a 5xx does the same. See §1/§2.
2. **`ApiErrorCode` didn't contain every code the backend actually emits.** `RUN_NOT_FOUND`,
   `INVALID_REVIEW_STATUS`, `REVIEWER_REQUIRED`, `REVIEW_COMMENT_REQUIRED`,
   `INVALID_SUBJECT_REFERENCE`, `SUBJECT_REFERENCE_CONFLICT`, `INVALID_REVIEW_PRIORITY`,
   and every `AiMultiplanarV2ErrorCodeMapper` code (`AI_INVALID_REQUEST`,
   `AI_NO_PLANE_REQUESTED`, `AI_INPUT_NOT_FOUND`, `AI_MODEL_NOT_FOUND`,
   `AI_MODEL_PLANE_MISMATCH`, `AI_MODEL_NOT_READY`, `AI_CONTRACT_FALLBACK_DISABLED`,
   `AI_REAL_INFERENCE_FAILED`, `AI_INVALID_RESPONSE`, `AI_UNSUPPORTED_INFERENCE_MODE`,
   `AI_MODULE_ERROR`, `AI_MODULE_TIMEOUT`) were missing, so `ApiErrorCode.fromCode(...)`
   silently fell back to `UNKNOWN`'s category/retryable for all of them (the literal code
   string in the response body was still correct — only its category/retryable
   classification was wrong). **Fixed**: full catalog in §3, enforced by
   `ApiErrorCodeCoverageTest`.
3. **Activation/deactivation audited the email as `entityId` with no real actor/trace.**
   `AuthService.auditActivation` used the hardcoded actor `"backend-admin"`, the target's
   *email* as `entityId`, and an empty `traceId`. **Fixed**: `actorId = claims.subject()`
   (the acting ADMIN's technical id), `entityId = account.id()` (the target's technical
   id), `traceId` from the current request's MDC, `action` from the `AuditAction` catalog.
   See §10.
4. **An AI call could count as `aiCallsSucceeded` before its result was validated.** Both
   `runMultiplanarV1`/`runMultiplanarV2` recorded success right after the HTTP
   call/deserialization returned — before the adapter and the strict contract validator
   ran — so an HTTP 200 with an invalid contract was counted as a success, and a
   contract-violation could be double-counted against `aiContractViolations` (once in
   `AiServiceClient`, again in `ApiExceptionHandler`). **Fixed**: success is now recorded
   only after HTTP → deserialize → adapt → validate all complete; `AiServiceClient` is the
   sole authority for `AI_MULTIPLANAR_CONTRACT_VIOLATION`'s counter (`ApiExceptionHandler`
   no longer increments it). See §13/§13a.

Also closed: AI Module calls with nothing in MDC (a background/non-request-scoped call)
previously sent **no** `X-Trace-Id` header at all; they now send a freshly generated
`technical-<uuid>`, resolved once per call and reused consistently for both the header and
the v2 request body. See §9.

## 1. Architecture

```
Request
  → TraceIdFilter        (resolves/generates X-Trace-Id, MDC, structured request log, metrics)
  → SecurityHeadersFilter
  → CorsResponseFilter    (now writes the standard error body on preflight rejection)
  → AuthFilter            (writes the standard error body via ApiErrorWriter; sets
                            actorId/roles request attributes for TraceIdFilter to log)
  → Controller / Service
  → ApiExceptionHandler   (@RestControllerAdvice; single mapping point to the error
                            contract; uses ApiErrorWriter to build the body)
```

Two new small packages carry the error contract:

- `ar.edu.uade.pfi.backend.config.error` — `ApiErrorCategory`, `ApiErrorCode`,
  `ApiErrorResponse`, `ApiErrorWriter`.
- `ar.edu.uade.pfi.backend.config.SafeLogSanitizer` — shared redaction utility.
- `ar.edu.uade.pfi.backend.service.OperationalMetricsService` — in-memory counters.
- `ar.edu.uade.pfi.backend.service.AuditAction` — stable audit-action catalog.

## 2. The one error contract — `ApiErrorResponse`

Every error response (auth filter, CORS rejection, or any exception through
`ApiExceptionHandler`) now has exactly these fields:

```json
{
  "status": "error",
  "code": "NOT_FOUND",
  "message": "Recurso no encontrado",
  "traceId": "trace-...",
  "path": "/api/studies/x",
  "method": "GET",
  "timestamp": "2026-...",
  "category": "RESOURCE",
  "retryable": false,
  "humanReviewRequired": true,
  "notClinicalDiagnosis": true
}
```

All fields the frontend already consumed (`status`, `code`, `message`, `traceId`,
`path`, `method`, `timestamp`, `humanReviewRequired`, `notClinicalDiagnosis`) are
unchanged in name and meaning — `category` and `retryable` are additive. Per-exception
extra fields (e.g. `AssetContentUnavailableException` adding `runId`/`plane`/`assetName`)
are still supported — `ApiErrorWriter.body(...)` returns a plain, ordered `Map` that
`ApiExceptionHandler` can extend before returning it.

`ApiErrorWriter` (`config/error/ApiErrorWriter.java`) is the single place that builds and
writes this contract:

- `ApiExceptionHandler` calls `apiErrorWriter.body(...)` and lets Spring MVC serialize
  the returned `Map` (so it can still merge in extra domain fields).
- `AuthFilter` and `CorsResponseFilter` — which run as raw servlet filters, before
  Spring MVC — call `apiErrorWriter.writeError(...)` directly against the
  `HttpServletResponse`. This replaced `AuthFilter`'s old hand-built JSON string
  concatenation and `CorsResponseFilter`'s old bare-status (no body) preflight
  rejection.

`ApiErrorWriter` never throws, never includes a stack trace, the original exception
message, the request body, the query string, or the `Authorization` header. It always
sets `Content-Type: application/json`, `Cache-Control: no-store`, and `X-Trace-Id`.

## 3. Code catalog — `ApiErrorCode`

`config/error/ApiErrorCode.java` is the stable catalog. Every code the frontend already
consumed is preserved unchanged — this is a classification pass, not a renaming one:

| Code | Category | Transient by default |
|---|---|---|
| `AUTHENTICATION_REQUIRED` | AUTHENTICATION | no |
| `ACCESS_DENIED` | AUTHORIZATION | no |
| `AUTH_STATE_UNAVAILABLE` | AUTHENTICATION | **yes** |
| `ADMIN_ACCOUNT_PROTECTED` | SECURITY | no |
| `LAST_ADMIN_PROTECTION` | SECURITY | no |
| `VALIDATION_ERROR` | VALIDATION | no |
| `BAD_REQUEST` | VALIDATION | no |
| `NOT_FOUND` | RESOURCE | no |
| `STUDY_NOT_FOUND` | RESOURCE | no |
| `ASSET_CONTENT_UNAVAILABLE` | RESOURCE | no |
| `CONFLICT` | RESOURCE | no |
| `DATABASE_UNAVAILABLE` | DATABASE | **yes** |
| `UPSTREAM_UNAVAILABLE` | AI_UPSTREAM | **yes** |
| `AI_TIMEOUT` | AI_UPSTREAM | **yes** |
| `AI_CONTRACT_VIOLATION` | AI_CONTRACT | no |
| `AI_MULTIPLANAR_CONTRACT_VIOLATION` | AI_CONTRACT | no |
| `INPUT_TOO_LARGE` | VALIDATION | no |
| `RUN_REVIEW_ERROR` | RESOURCE | no — **reserved/historical, never actually emitted** (RunReviewException always carries a more specific code below) |
| `CLIENT_ERROR` (generic 4xx fallback) | VALIDATION | no |
| `INTERNAL_ERROR` | INTERNAL | no |
| `RUN_NOT_FOUND` | RESOURCE | no |
| `INVALID_REVIEW_STATUS` | VALIDATION | no |
| `REVIEWER_REQUIRED` | VALIDATION | no |
| `REVIEW_COMMENT_REQUIRED` | VALIDATION | no |
| `INVALID_SUBJECT_REFERENCE` | VALIDATION | no |
| `SUBJECT_REFERENCE_CONFLICT` | RESOURCE | no |
| `INVALID_REVIEW_PRIORITY` | VALIDATION | no |
| `AI_INVALID_REQUEST` | VALIDATION | no |
| `AI_NO_PLANE_REQUESTED` | VALIDATION | no |
| `AI_INPUT_NOT_FOUND` | RESOURCE | no |
| `AI_MODEL_NOT_FOUND` | RESOURCE | no |
| `AI_MODEL_PLANE_MISMATCH` | AI_CONTRACT (documented choice — see catalog javadoc) | no |
| `AI_MODEL_NOT_READY` | AI_UPSTREAM | no |
| `AI_CONTRACT_FALLBACK_DISABLED` | AI_CONTRACT | no |
| `AI_REAL_INFERENCE_FAILED` | AI_UPSTREAM | no (never auto-retried on POST regardless) |
| `AI_INVALID_RESPONSE` | AI_CONTRACT | no |
| `AI_UNSUPPORTED_INFERENCE_MODE` | VALIDATION | no |
| `AI_MODULE_ERROR` | AI_UPSTREAM | **yes** (forced false on a non-idempotent inference POST regardless — §5) |
| `AI_MODULE_TIMEOUT` | AI_UPSTREAM | **yes** (same override) — this is the code v2 timeouts actually use |
| `UNKNOWN` (defensive fallback only) | INTERNAL | no |

**P10-B.1**: this table is now audited complete — every code path in
`ApiExceptionHandler`, `AuthFilter`, `RunReviewException`, `StudyMetadataException`,
`AiMultiplanarUpstreamException`, and `AiMultiplanarV2ErrorCodeMapper` resolves to a real
entry here, enforced by `ApiErrorCodeCoverageTest` (an explicit, versioned list of domain
codes — not a filesystem-path-dependent search). `AI_TIMEOUT` remains as a
reserved/historical entry — no current code path emits that literal string;
`AI_MODULE_TIMEOUT` is what v2 timeouts actually use. No historical alias renames were
needed — `codeForStatus()` in `ApiExceptionHandler` (mapping a bare
`ResponseStatusException` status to a code) is unchanged from P10-A.

`AiMultiplanarV2ErrorCodeMapper.Mapped` (P10-B.1 §7) now carries a typed `ApiErrorCode
backendCode` instead of a free `String` — upstream AI Module codes can never inject an
arbitrary string into the backend's public code; only this fixed mapping table decides
what comes out.

## 4. Categories

`ApiErrorCategory`: `AUTHENTICATION`, `AUTHORIZATION`, `VALIDATION`, `RESOURCE`,
`DATABASE`, `AI_UPSTREAM`, `AI_CONTRACT`, `SECURITY`, `INTERNAL`. Fixed, low-cardinality
enum — safe to use as a log/metric field.

## 5. `retryable`

`ApiErrorWriter.resolveRetryable(code, status, method, path)`:

1. `retryable = true` only when the **code itself** is one of the four explicitly
   transient codes: `DATABASE_UNAVAILABLE`, `AUTH_STATE_UNAVAILABLE`,
   `UPSTREAM_UNAVAILABLE`, `AI_TIMEOUT`.
2. An HTTP 502/503/504 status is used **only** as a defensive fallback for a code this
   catalog doesn't recognize (`ApiErrorCode.UNKNOWN`) — a *known*, explicitly-classified
   non-transient code (e.g. `AI_CONTRACT_VIOLATION`, which is itself mapped to HTTP 502)
   must never be upgraded to `retryable=true` just because of its HTTP status. This was a
   real bug caught by `ApiExceptionHandlerContractTest.aiContractViolationIsNeverRetryable`
   during development of this feature — the first implementation used status alone as an
   OR-condition and incorrectly marked contract violations retryable.
3. **Non-idempotency override**: regardless of (1)/(2), a `POST` to
   `/multiplanar/run`, `/pipeline/run`, or `/inputs` (any path containing those
   segments) always forces `retryable=false` — the backend cannot guarantee the AI
   Module's side effect wasn't already applied, so it never claims a retry is safe for
   inference/upload endpoints. This is the explicit, documented conservative choice the
   task asked for instead of inventing an idempotency-key mechanism.

`false` for: 400/401/403/404/409, every contract-violation code, every
authorization/security code, and every internal error without a proven safe-retry story.

## 6. Sanitization — `SafeLogSanitizer`

`config/SafeLogSanitizer.java`, stateless, two granularities:

- `redactValue(String)` — whole-value semantics: sensitive → `"[redacted]"` entirely.
  Use for one discrete field (a header, a claim, an audit metadata value).
- `sanitizeMessage(String)` — substring-level redaction inside a longer free-text
  message (an exception message, a log line) — known-sensitive substrings are
  individually replaced, the rest of the message survives, so log lines stay useful.

Detects (regex-based, defense-in-depth, not a perfect DLP tool): Bearer tokens, JWTs,
any `jdbc:` URL (credentials or not — P10-B.1: previously only JDBC URLs *with* explicit
credentials were caught), URLs with userinfo, **any `http(s)://` URL at all** (P10-B.1
addition), `localhost`/`127.0.0.1`/`0.0.0.0`/`host.docker.internal`/
`*.trycloudflare.com`/`*.internal` hosts (P10-B.1 addition), private IPv4 addresses —
`10.0.0.0/8`, `172.16.0.0/12`, `192.168.0.0/16` (P10-B.1 addition), Windows paths, `/tmp`
and `/app` paths, common Linux system-directory paths (`/var`, `/opt`, `/home`, `/etc`,
`/usr`, `/srv`, `/data`, `/mnt`, `/root` — P10-B.1 addition), full query strings,
`key=value`/`key: value` secret pairs (password/secret/token/credential/authorization/
apikey), emails, common medical/image filenames (`.dcm`, `.nii`, `.png`, `.jpg`, ...),
and a length/non-printable-ratio heuristic for binary-looking content. Every result is
capped at 200 characters. See `SafeLogSanitizerTest` for the full coverage (synthetic
examples only, never a real secret).

## 6b. `DiagnosticsSanitizer` (P10-B.1 §5)

`service/DiagnosticsSanitizer.java` — a second, purpose-built recursive sanitizer for AI
Module diagnostics responses embedded in `GET /api/system/diagnostics`. Unlike
`AuditService.sanitize()`, it's a **blocklist**, not a strict allowlist: the upstream
health/readiness/models responses have many benign fields (`schemaVersion`, model
key/version, artifact hash, quality status, `multiplanarEndpoint`, ...) that must survive
unchanged for v1/v2 verification to keep working. It drops keys matching an **exact,
case-insensitive** name — `url`, `baseUrl`, `path`, `file`, `filename`, `localPath`,
`storagePath`, `token`, `secret`, `credential`, `authorization`, `password`, `host`,
`port` — and, separately, redacts any remaining string *value* that
`SafeLogSanitizer.isSensitive(...)` flags, recursively over nested maps/lists (capped at
depth 6 / 50 list elements).

Exact-match, not substring-match, on purpose: an early substring-based implementation
wrongly redacted `reportCount` (contains `"port"`) and would have wrongly redacted
`profile` (contains `"file"`) — caught by `DiagnosticsSanitizerTest` during development.

`SystemDiagnosticsService` applies it to every upstream response it embeds (`health`,
`readiness`, `models`, `getEvaluationEvidence`, `warmup`) and no longer puts
`compact(ex.getMessage())` into a diagnostics field on failure — fixed messages instead:
*"AI Module no disponible."*, *"Readiness no disponible."*, *"Evidencia no disponible."*,
*"Modelos no disponibles."*

## 7. Logs

- `ApiExceptionHandler` never logs `ex.getMessage()` and never logs the exception's
  fully-qualified class name — only `ex.getClass().getSimpleName()`. Line format:
  `event=api_error traceId=... code=... category=... status=... method=... path=...
  exceptionType=... retryable=...`. A sanitized detail line (`event=api_error_detail`,
  with `SafeLogSanitizer.sanitizeMessage(ex.getMessage())`) is only emitted at DEBUG —
  never the default production log level, and never the public response body.
- `TraceIdFilter` emits the final request line:
  `event=http_request traceId=... method=... path=... status=... durationMs=...
  outcome=success|client_error|server_error`, plus `actorId=... roles=...` **only** when
  `AuthFilter` already placed them on the request (see §8) — never derived from an
  unrevalidated token. Never logs email, name, request body, query string, case
  metadata, file names, or the `Authorization` header.
- `AuditService.record()` failures log `event=audit_write_failed traceId=... action=...
  exceptionType=...` — no metadata, no stack.

## 8. Trace ID

Unchanged rules from P10-A, reconfirmed and now more thoroughly tested
(`TraceIdFilterTest`): sanitized to `[a-zA-Z0-9._:-]`, capped at 96 chars, a
blank/invalid header generates `trace-<uuid>`, always returned as `X-Trace-Id`, stored
as a request attribute and in MDC, MDC always cleared in `finally` (verified even when
the downstream controller throws, and under concurrent requests with no cross-thread
MDC contamination).

**New in P10-B**: `AuthFilter` now also sets `pfi.auth.actorId` (the revalidated JWT
subject) and `pfi.auth.actorRoles` (the *effective*, post-revalidation, known-role-only
list) as request attributes right before calling `filterChain.doFilter`. Because
`TraceIdFilter` wraps every other filter (`@Order(HIGHEST_PRECEDENCE)`), its `finally`
block runs *after* the whole chain — including `AuthFilter` — so it can read those
attributes without depending on any controller and without ever touching the raw,
unrevalidated token itself.

## 9. Propagation to the AI Module

`AiServiceClient` (`client/AiServiceClient.java`) now sends `X-Trace-Id: <current MDC
trace id>` on **every** call it makes — `health`, `readiness`, `models`, `verifyModels`,
`warmup`, `syncModels`, `runPipeline`, `uploadInput`, `getAsset`,
`runMultiplanar`/v1/v2, `getAgentReport*`, `getEvaluationSummary`/`Evidence`,
`getMultiplanarContract`. Previously only the v2 multiplanar-run call sent an actual
header (v1/pipeline embedded the trace id in the request body's `metadata` map instead,
which is preserved unchanged for backward contract compatibility). `TraceIdConsistencyGuard`
(unchanged) still validates that the v2 request body's `traceId` matches the header.

**P10-B.1**: for a call with nothing in MDC (a background/non-HTTP-request process), the
client now sends a freshly generated `technical-<uuid>` instead of omitting the header
(the P10-B behavior). The id is resolved once per logical call
(`AiServiceClient.resolveTraceId()`) and reused consistently for that call's header and,
for the v2 contract, its request body's `traceId` field too — never written back to MDC,
never shared across threads/calls. Verified end-to-end with WireMock in
`AiTracePropagationTest` (`technicalTraceIdIsSentWhenThereIsNoCurrentTraceId`,
`eachCallWithoutMdcGetsItsOwnFreshTechnicalTraceId`,
`v2CallWithoutMdcUsesTheSameTechnicalTraceIdInBodyAndHeader`).

**Never sent to the AI Module**: the user's JWT/access token, refresh token, email, or
roles — this was already true before P10-B (confirmed by re-reading `AiServiceClient` in
full — no `Authorization` header is ever set), and is now explicitly regression-tested.

## 10. Audit catalog — `AuditAction`

`service/AuditAction.java`: `AUTH_LOGIN_SUCCEEDED`, `AUTH_LOGIN_FAILED`,
`AUTH_REFRESH_SUCCEEDED`, `AUTH_REFRESH_FAILED`, `PROFESSIONAL_ACTIVATED`,
`PROFESSIONAL_DEACTIVATED`, `ADMIN_BOOTSTRAP_CREATED`, `AI_INPUT_UPLOADED`,
`AI_RUN_REQUESTED`, `AI_RUN_COMPLETED`, `AI_RUN_FAILED`, `REVIEW_SAVED`,
`REVIEW_UPDATED`, `ASSET_REQUESTED`, `ACCESS_DENIED`, `SECURITY_STATE_UNAVAILABLE`.

**Deliberately not migrated wholesale**: existing historical literal action strings
(`"asset.snapshot.stored"`, `"auth.login.completed"`, `"access.denied"`,
`"multiplanar.run.completed"`, `"review.updated"`, ...) are untouched where two existing
tests assert on their exact literal (`AuditServiceTest`, `AiRunReviewControllerTest` both
assert `action == "review.updated"` — renaming that specific one would need an explicit
compatibility strategy this pass didn't invent, per the task's own instruction). The
catalog exists so *new*/touched critical operations have one place to draw a stable
action name from.

**P10-B.1 §11 — actual consumers** (the catalog is no longer purely aspirational):

- `AuthService.activate`/`deactivate` now pass `AuditAction.PROFESSIONAL_ACTIVATED.name()`
  / `AuditAction.PROFESSIONAL_DEACTIVATED.name()` (previously the literal strings
  `"PROFESSIONAL_ACTIVATED"`/`"PROFESSIONAL_DEACTIVATED"` — same value, now sourced from
  the enum instead of a hand-typed literal that could drift).
- `AiMultiplanarController.auditStrictFailure` now uses
  `AuditAction.AI_RUN_FAILED.name()` instead of the old ad hoc
  `"multiplanar.real_baseline.failed"` string (no test asserted on that literal, so this
  one *was* safe to rename).
- `REVIEW_SAVED`/`AUTH_LOGIN_SUCCEEDED`/`AUTH_LOGIN_FAILED`/`AUTH_REFRESH_SUCCEEDED`/
  `AUTH_REFRESH_FAILED`/`ADMIN_BOOTSTRAP_CREATED`/`AI_INPUT_UPLOADED`/
  `AI_RUN_REQUESTED`/`AI_RUN_COMPLETED`/`REVIEW_UPDATED`/`ASSET_REQUESTED`/
  `ACCESS_DENIED`/`SECURITY_STATE_UNAVAILABLE` remain unconsumed — each maps to an
  existing call site that already has its own tested literal (e.g. `RunReviewService`'s
  audit event is a raw `DomainAuditEvent` built inline with `"review.updated"`, checked by
  name in two tests), so migrating them needs the same one-at-a-time, test-verified
  treatment as the two done here, not a blanket rename.

Audit events already carried (unchanged): `actorId`, `action`, `entityId`, `traceId`,
`timestamp`, `outcome` (via metadata), sanitized `metadata`. **P10-B.1 §9**: for
activation/deactivation specifically, `actorId` is now `claims.subject()` (the acting
ADMIN's technical id, not the hardcoded string `"backend-admin"`), `entityId` is now
`account.id()` (the target account's technical id, not its email — closing a real gap:
P10-B's own text here previously said this was "unchanged since P10-A.2.1" and used the
email, which was itself never correct for this hardening effort), and `traceId` comes
from the current request's MDC (not an empty string).

## 11. `AuditService` hardening

`sanitize()` now does **value-based** detection in addition to the existing key-name
filtering: a value like `metadata["value"] = "Bearer eyJ..."` is redacted to
`"[redacted]"` even though `"value"` isn't a sensitive key name, via
`SafeLogSanitizer.isSensitive(...)`. New caps (all defense-in-depth against accidental
metadata growth, not expected to bind in normal operation):

- max metadata entries per level: 40
- max recursion depth: 5 (a map nested past this is dropped, not partially rendered)
- max list elements: 20
- max string length: 160 (unchanged from before P10-B)

**P10-B.1 §10**: the same field-level controls now apply to `actor`, `entityId`,
`traceId`, and `action` too — previously only `metadata` was sanitized, so a call site
could (and, in `AuthService.auditActivation`'s case per §0/§10 above, did) pass an email
straight into `entityId` and have it persisted verbatim. `record()` now runs every field
through:

- `actor`/`entityId` → `SafeLogSanitizer.isSensitive(...)` whole-value check (an email,
  token, path, or URL becomes `"[redacted]"` entirely, not partially masked), capped at
  100 characters.
- `action` → must match `^[A-Za-z0-9_.]{1,80}$`; anything else has its unsafe characters
  replaced with `-` rather than being silently dropped or rejected outright.
- `traceId` → the same sanitize-and-cap contract as `TraceIdFilter`'s own incoming-header
  handling (`[a-zA-Z0-9._:-]` only, capped at 96 chars), so an audited traceId always
  matches what a caller could actually search logs for.

No historical audit *event already persisted* is retroactively modified — this only
changes what a future `record()` call writes. Covered by `AuditServiceSanitizationTest`,
including `emailAttemptedAsEntityIdIsNeverPersisted` (the exact regression scenario from
§0 item 3).

## 12. Audit failures never mask the real operation

**This closes a real, pre-existing gap.** Before P10-B, some `auditService.record(...)`
call sites had their own try/catch (`ApiExceptionHandler`, `AuthService.auditActivation`,
`RunAssetSnapshotService.audit`, `RoleAuthorizationService`), but others had **none at
all** — `AuthController`'s login-audit call and `AiBackendService.audit`/
`AiMultiplanarController`'s run-audit calls could have let a Postgres failure during
audit persistence propagate and fail the primary operation (a login, an inference run)
that had already succeeded.

`AuditService.record()` itself is now fail-safe: it catches any `RuntimeException` from
`repository.saveAuditEvent`, logs a sanitized `event=audit_write_failed` line, increments
`OperationalMetricsService.auditWriteFailures`, and returns `null` instead of
propagating. This protects every call site uniformly — old and new — without requiring
each one to remember its own try/catch. No retry loop was added (per the task's explicit
instruction not to retry audit writes in a loop).

## 13. Operational metrics — `OperationalMetricsService`

In-memory `LongAdder`/fixed-key counters (`service/OperationalMetricsService.java`), no
external platform wired yet. Counters: `httpRequestsTotal`, `httpResponses2xx/4xx/5xx`,
`authenticationFailures`, `authorizationDenials`, `authStateUnavailable`,
`databaseUnavailable`, `aiCallsTotal/Succeeded/Failed`, `aiContractViolations`,
`reviewsSaved`, `auditWriteFailures`. Accumulated durations: `totalRequestDurationMs`,
`aiCallDurationMs` (exposed as computed averages, not raw sums, in the snapshot).

**Never keyed by** `traceId`/`caseId`/`runId`/`email`/`userId`/an arbitrary path/an
exception message — every counter is a fixed, enumerable field. Verified in
`OperationalMetricsServiceTest.snapshotHasFixedShapeWithNoCardinalityByRequestData`
(asserts the exact key count of both the top-level snapshot and the nested `counters`
map).

Wired at: `TraceIdFilter` (http totals/2xx/4xx/5xx/duration — the single choke point that
covers every request), `AuthFilter` (authenticationFailures/authorizationDenials/
authStateUnavailable for its own direct 401/403/503 writes), `ApiExceptionHandler`
(authenticationFailures/authorizationDenials for a `ResponseStatusException`-originated
401/403 that never went through `AuthFilter` — e.g. `RoleAuthorizationService`'s 403;
plus `databaseUnavailable`; `aiContractViolations` **only** for the single-plane
`AI_CONTRACT_VIOLATION` code — see the P10-B.1 note below), `AiServiceClient`
(aiCallsTotal/Succeeded/Failed/duration around the *whole* logical operation — §13a below
— plus `aiContractViolations` for `AI_MULTIPLANAR_CONTRACT_VIOLATION`), `RunReviewService`
(reviewsSaved on a successful `saveReview`), `AuditService` (auditWriteFailures).

### 13a. `aiCallsSucceeded` requires a validated canonical result (P10-B.1 §12)

P10-B recorded `aiCallsSucceeded` as soon as the HTTP call + JSON deserialization
returned — for `runMultiplanarV1`/`runMultiplanarV2`, that happened *before* the response
adapter and the strict contract validator ran, both of which live outside the original
`execute`/`executeV2` wrapper. An HTTP 200 with a response that then failed strict
contract validation was therefore counted as a **success**.

Fixed: `runMultiplanarV1`/`runMultiplanarV2` now wrap the *entire* pipeline — HTTP
dispatch (via metric-free `executeHttp`/`dispatchV2Http` helpers) → deserialize → adapt →
validate — in one try/catch that calls `recordAiCall(true, ...)` only after the validator
returns successfully, and `recordAiCall(false, ...)` on any exception from any stage
(transport, timeout, JSON, adapter, validator, `TraceIdConsistencyGuard`, or contract).
Every other `AiServiceClient` call (`health`, `models`, `getAsset`, ...) still records
success/failure automatically around just the HTTP call itself, since there is no further
validation stage for those. Verified by
`AiServiceClientContractViolationMetricsTest.aHttp200WithAnInvalidContractNeverCountsAsSucceeded`.

**P10-B.1 §13 — exactly-once contract-violation counting.** P10-B had
`ApiExceptionHandler.recordMetrics` increment `aiContractViolations` for *both*
`AI_CONTRACT_VIOLATION` and `AI_MULTIPLANAR_CONTRACT_VIOLATION`, while `AiServiceClient`
*also* incremented it for every `AiMultiplanarContractViolationException` it caught
(`TraceIdConsistencyGuard` mismatches, and — after this pass — both v1's and v2's strict
validator failures) — every multiplanar contract violation was counted twice. Fixed by
splitting authority per code: `AiServiceClient` is the **sole** counter for
`AI_MULTIPLANAR_CONTRACT_VIOLATION` (it's the only place that sees the full
HTTP→adapter→validator pipeline for a multiplanar run), and `ApiExceptionHandler` remains
the only counter for the older, single-plane `AI_CONTRACT_VIOLATION` code (raised from
`AiBackendService`/`SagittalRealBaselineContractValidator`, a path `AiServiceClient` never
sees). Verified by a WireMock test asserting `aiContractViolations` increases by exactly
1 for a single multiplanar contract violation.

Every new dependency on `OperationalMetricsService` (and `ApiErrorWriter` where relevant)
is `@Nullable` on its `@Autowired` constructor parameter — a `@WebMvcTest` slice that
doesn't wire the full context still works unchanged, with metrics simply not recorded.

## 14. ADMIN diagnostics — `observability` section

`GET /api/system/diagnostics` (ADMIN-only, unchanged authorization) now includes:

```json
"observability": {
  "uptimeSeconds": 1234,
  "counters": { "httpRequestsTotal": 10, "...": "..." },
  "averageRequestDurationMs": 42,
  "averageAiCallDurationMs": 120,
  "auditWriteFailures": 0,
  "timestamp": "2026-..."
}
```

Never includes emails, actorIds, caseIds, runIds, individual trace ids, URLs, secrets,
paths, file names, or stack traces — every field is a fixed counter or average. If
`OperationalMetricsService` isn't wired (a slice test, or a future constructor path that
omits it), the section degrades to `{"status": "unavailable"}` rather than throwing. See
`ObservabilityDiagnosticsTest`.

The public liveness endpoint, `GET /api/system/health`, is unchanged — still exactly
`{"status": "ok"}`, no auth, no other fields, ever.

## 15. Health/readiness/diagnostics separation (unchanged)

- `GET /api/system/health` — public, `{"status":"ok"}` only.
- `GET /api/ai/health` — professional/ADMIN (unchanged from P10-A.1).
- `GET /api/ai/readiness` — ADMIN (unchanged from P10-A.1).
- `GET /api/system/diagnostics` — ADMIN, now includes `observability` (§14).

No endpoint's authorization level changed in P10-B.

## 16. Upstream error classification (AI Module)

| Condition | Code | Status |
|---|---|---|
| Timeout | `AI_MODULE_TIMEOUT` | 504 |
| Connection refused / host unreachable / unstructured 5xx | `UPSTREAM_UNAVAILABLE` (generic calls) / `AI_MODULE_ERROR` (v2 dispatch) | 502/503 |
| Upstream 4xx (v1/generic, no structured DTO) | fixed local message, upstream status preserved | matches upstream status |
| Upstream 4xx/5xx (v2, structured `AiStructuredErrorV2Dto.code`) | mapped via `AiMultiplanarV2ErrorCodeMapper` to one of `AI_INVALID_REQUEST`/`AI_NO_PLANE_REQUESTED`/`AI_INPUT_NOT_FOUND`/`AI_MODEL_NOT_FOUND`/`AI_MODEL_PLANE_MISMATCH`/`AI_MODEL_NOT_READY`/`AI_CONTRACT_FALLBACK_DISABLED`/`AI_REAL_INFERENCE_FAILED`/`AI_INVALID_RESPONSE`/`AI_UNSUPPORTED_INFERENCE_MODE` | per mapping table in `AiMultiplanarV2ErrorCodeMapper` |
| Malformed/unstructured JSON body on a v2 error | `AiMultiplanarV2ErrorCodeMapper.UNKNOWN` → `AI_MODULE_ERROR` | 502 |
| Trace id mismatch (v2 body vs. header) | `AI_MULTIPLANAR_CONTRACT_VIOLATION` | 502 |

**P10-B.1 §3**: the upstream response body is now used **only** to deserialize
`AiStructuredErrorV2Dto` — never copied into the public message. When the body isn't
structured (or isn't v2 at all), the public message is always one of the fixed strings in
§0 item 1/§3's catalog, regardless of what the AI Module actually said. The AI Module's
base URL/host/port are never included in any error message, log line, or diagnostics
field (verified by `SystemDiagnosticsServiceTest` and `SensitiveDataLeakRegressionTest`).

## 17. Database error classification (unchanged)

`DatabaseUnavailableException` → `DATABASE_UNAVAILABLE`, 503 — used when Postgres itself
is unreachable/misconfigured. A genuinely missing resource (a study, a run, a review)
still returns its own specific 404 code (`STUDY_NOT_FOUND`, `RUN_NOT_FOUND` via
`RunReviewException`, etc.) rather than being folded into the database-unavailable
bucket. No SQL, table/column name, JDBC driver name, JDBC URL, or DB username is ever
returned — confirmed by `SensitiveDataLeakRegressionTest` and the existing `AuditServiceTest`.

## 18. Tests

New suites from P10-B (all passing, see §23):

- `ApiErrorWriterTest` — contract fields, retryable resolution (including the
  contract-violation-on-502 regression case), header/body writing, never throws.
- `ApiExceptionHandlerContractTest` — every exception type produces the full contract;
  401 and 403 have identical shape; internal errors never leak the raw message.
- `SafeLogSanitizerTest` — every listed sensitive pattern, plain values pass through,
  length cap.
- `TraceIdFilterTest` (extended) — invalid characters sanitized not rejected, >96 chars
  truncated, whitespace-only falls back to generated, MDC cleared even when the
  controller throws, 16 concurrent requests never cross-contaminate MDC.
- `AuditServiceSanitizationTest` — value-based redaction under generic keys, depth/list/
  entry caps, `record()` never throws and increments the failure metric.
- `OperationalMetricsServiceTest` — every counter increments correctly, averages compute
  correctly, fixed key-count assertion (no per-request cardinality).
- `AiTracePropagationTest` (WireMock) — every AI Module call carries a trace id header.
- `ObservabilityDiagnosticsTest` — sanitized shape, graceful degradation without metrics.
- `SensitiveDataLeakRegressionTest` — end-to-end sweep across `AuthFilter`,
  `ApiExceptionHandler`, AI-upstream exceptions, and diagnostics.

**New suites from P10-B.1**:

- `ApiErrorCodeCoverageTest` (`client` package, alongside the package-private
  `AiMultiplanarV2ErrorCodeMapper`) — every `AiMultiplanarV2ErrorCodeMapper` result, every
  `RunReviewService`/`StudyMetadataException`/`AuthFilter` code, and every generic-status
  code resolves to a real catalog entry, never `UNKNOWN`. Uses an explicit, versioned list
  of domain codes rather than a filesystem-path-dependent search, per the task's own
  instruction.
- `DiagnosticsSanitizerTest` — drops exact sensitive keys without false-positiving on
  substrings (`reportCount`/`profile`), preserves allowed diagnostic fields, redacts
  sensitive values under allowed keys, recurses into nested maps/lists.
- `AiServiceClientContractViolationMetricsTest` (WireMock) — a single strict-validator
  failure increments `aiContractViolations` by exactly 1 (not 2); an HTTP 200 with an
  invalid contract never increments `aiCallsSucceeded`.
- `AuditServiceSanitizationTest` (extended) — `emailAttemptedAsEntityIdIsNeverPersisted`,
  path-like actor redaction, unsafe-character stripping in `action`, oversized `traceId`
  capping.
- `AiTracePropagationTest` (extended) — `technicalTraceIdIsSentWhenThereIsNoCurrentTraceId`,
  `eachCallWithoutMdcGetsItsOwnFreshTechnicalTraceId`,
  `v2CallWithoutMdcUsesTheSameTechnicalTraceIdInBodyAndHeader`,
  `aiCallsNeverCarryJwtEmailOrRolesHeaders` (replaces the old P10-B
  "no header sent" test, which is no longer correct behavior).
- `SensitiveDataLeakRegressionTest` (extended) — a single synthetic "poison" message
  (JDBC URL + `trycloudflare.com` + `localhost` + `/tmp` + `/app` + Windows path +
  password + token + email, all combined) run through `DatabaseUnavailableException`,
  `AiContractViolationException`, `AiMultiplanarContractViolationException`,
  `AiMultiplanarUpstreamException`, `ResponseStatusException` at 502/503, ADMIN
  diagnostics with a failing AI Module, a captured Logback `ListAppender` (default log
  level, not DEBUG), and a captured audit event — confirmed absent from all of them.
- `ProfessionalActivationIntegrationTest`/`ApprovalEndpointControllerTest`/
  `LastAdminProtectionTest`/`ProfessionalActivationControllerTest` — unchanged assertions,
  re-verified green against the new `auditActivation` signature (no test asserted on the
  old `"backend-admin"`/email-as-entityId behavior, so nothing needed updating there —
  only `ApiExceptionHandlerContractTest`/`ApiExceptionHandlerTest` needed a one-character
  message-text update, `"Error interno del backend"` → `"Error interno del backend."`,
  to match the task-mandated fixed catalog string).

Existing suites updated only where the constructor surface genuinely grew (additive
`@Nullable` parameters) or a fixed message gained the task-mandated trailing period:
`SystemDiagnosticsServiceTest` (constructor now takes 9 params),
`ApiExceptionHandlerContractTest`/`ApiExceptionHandlerTest` (message text). No test's
*functional* assertions about auth/roles/bootstrap/activation/studies/assets/review/the
multiplanar contract/the presenter changed.

## 19. Limitations — honest, not to be oversold

- **Metrics are in-memory only.** They reset on every process restart/redeploy and are
  per-instance (no aggregation across multiple Railway/GCP instances). This is
  deliberately a stopgap, not a Prometheus/OpenTelemetry exporter.
- **No distributed tracing.** `X-Trace-Id` correlates log lines and the AI Module call
  within a single request's lifetime; there is no span/parent-child tracing model, no
  OpenTelemetry SDK, no trace sampling.
- **No Cloud Logging / Cloud Monitoring integration yet.** Logs are structured
  (`event=...` key-value lines) so they *will* parse cleanly once Cloud Logging's
  structured-log ingestion is configured, but that configuration doesn't exist yet.
- **No WAF, no distributed rate limiting** — unchanged from P10-A.
- **No multi-tenancy** — unchanged from P10-A.
- **The AI Module is temporarily reachable behind a Cloudflare tunnel** — an operational,
  not application-level, control; unchanged from P10-A.
- **`SafeLogSanitizer` is a heuristic, not a certified DLP tool.** It catches the
  documented pattern classes; it is not a guarantee against every possible way sensitive
  data could end up in a string.
- This is **not a clinical diagnostic tool** — `humanReviewRequired`/
  `notClinicalDiagnosis` remain `true` everywhere, unchanged.

## 20. Future integration with Google Cloud (not implemented here)

- Cloud Logging: ingest the existing `event=...` structured lines directly (no format
  change needed) via the standard GCP logging agent/sidecar once deployed there.
- Cloud Monitoring: export `OperationalMetricsService`'s counters as custom metrics (or
  replace it with the Cloud Monitoring SDK / OpenTelemetry Collector) once the service
  runs on GCP; the current in-memory service is a deliberately simple placeholder with
  the same counter names to make that swap mostly mechanical.
- Cloud Trace: adopt OpenTelemetry's trace-context propagation (`traceparent` header) in
  addition to (not instead of) `X-Trace-Id`, so existing correlation keeps working during
  a gradual migration.

## 21. No changes to

Frontend, AI Module, Railway/GCP infrastructure, the multiplanar contract (v1 or v2),
`schema pfi.multiplanar-run.v2`, the sagittal/axial models, hashes, manifests,
measurements, quality gates, study persistence, `raw_*` semantics,
`humanReviewRequired`, `notClinicalDiagnosis`. Verified via `git status`/`git diff` scope
and by the full pre-existing test suite (351 tests from P10-A.2.2, then 414 after P10-B)
staying functionally green — P10-B.1 touched `config/`, `config/error/`,
`auth/AuthFilter.java`, `auth/AuthService.java` (activation audit only),
`controller/AiMultiplanarController.java` (one audit action string), `service/`
audit/metrics/review/diagnostics files, `client/AiServiceClient.java`, and
`client/AiMultiplanarV2ErrorCodeMapper.java` — no controller route, DTO shape, or
persistence schema changed.

## 22. Rollback

Every change is additive or a narrowing of an existing surface, and there is no schema
migration:

- `ApiErrorWriter`/`ApiErrorCode`/`ApiErrorResponse`/`SafeLogSanitizer`/
  `OperationalMetricsService`/`AuditAction`/`DiagnosticsSanitizer` (P10-B.1) are all new
  files — deleting them and reverting their few call sites restores the prior behavior
  exactly.
- Every new constructor dependency added to an existing class (`AuthFilter`,
  `CorsResponseFilter`, `TraceIdFilter`, `ApiExceptionHandler`, `AuditService`,
  `AiServiceClient`, `RunReviewService`, `SystemDiagnosticsService`) is either
  `@Nullable` or has a backward-compatible delegating overload — a `git revert` of this
  commit needs no follow-up migration.
- No data was migrated, no table was altered, no environment variable is newly
  *required* (all new behavior activates automatically once `OperationalMetricsService`
  is on the classpath — there is no new required config).
- P10-B.1 specifically: `ApiErrorCode.publicMessage()` and `DiagnosticsSanitizer` are pure
  functions with no state; `AiMultiplanarV2ErrorCodeMapper.Mapped.backendCode()` changing
  type from `String` to `ApiErrorCode` is a package-private, non-breaking change (the
  class itself is `final class` inside the `client` package, never exposed publicly) — a
  revert of just the P10-B.1 commit is independent of the P10-B commit beneath it.

## 23. Evidence of tests

```
$env:JAVA_HOME = "C:\Program Files\Eclipse Adoptium\jdk-21.0.11.10-hotspot"
$env:Path = "$env:JAVA_HOME\bin;$env:Path"
mvn clean test
```

**BUILD SUCCESS. Total tests: 454, Failures: 0, Errors: 0, ~58s** (was 351 at P10-A.2.2,
414 after P10-B; +40 new tests in this P10-B.1 pass, 0 removed). Behavior-changing
modifications to pre-existing test assertions were limited to exactly two: the
`"Error interno del backend"` → `"Error interno del backend."` message-text update
(`ApiExceptionHandlerContractTest`, `ApiExceptionHandlerTest`) and the additive
`@Nullable`-only `SystemDiagnosticsServiceTest` constructor-arity assertion from P10-B.
No auth/roles/bootstrap/activation/study/asset/review/contract-v1/contract-v2/presenter
assertion changed. Compiled with `--release 17` (unchanged `pom.xml`), executed on
Temurin 21 per the task's instruction (`JAVA_HOME` must point at a JDK 21 install —
Mockito's inline mock maker cannot instrument classes under a JDK 25 `JAVA_HOME`, a
pre-existing environment caveat, not new here; Docker Desktop must also be running for
the Testcontainers-backed Postgres suites).

### Pending risks (honest, not resolved in this pass)

- `RUN_REVIEW_ERROR` and `AI_TIMEOUT` remain in the `ApiErrorCode` catalog as
  reserved/historical entries that no current code path emits — harmless, but worth
  removing in a future cleanup pass if they're confirmed to stay permanently dead.
- `REVIEW_SAVED`/most of the `AuditAction` catalog is still unconsumed by production code
  (only `PROFESSIONAL_ACTIVATED`/`PROFESSIONAL_DEACTIVATED`/`AI_RUN_FAILED` were migrated
  this pass) — `RunReviewService`'s own audit event (`"review.updated"`) is asserted by
  literal string in two existing tests and was deliberately left alone rather than forcing
  an unplanned rename/compatibility-shim decision.
- `DiagnosticsSanitizer`'s key blocklist is exact-match, not pattern-based — a future
  upstream field named e.g. `"internalHost"` would **not** be caught by key name alone
  (though its *value*, if it looks like a host/URL/path, still would be, via
  `SafeLogSanitizer.isSensitive`). This is a deliberate precision-over-recall tradeoff
  after the `reportCount`/`port` false-positive found during development.
