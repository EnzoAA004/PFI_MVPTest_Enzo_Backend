# P10-B — Unified Error Contract, Tracing, Sanitized Audit & Operational Observability

Commit base: `4e206398471ec8ce6091e3892f1a7a1bee3f472d` (P10-A.2.2)
Repository: `EnzoAA004/PFI_MVPTest_Enzo_Backend`

P10-A/P10-A.1/P10-A.2/P10-A.2.1/P10-A.2.2 closed authentication, authorization, and
account-state integrity. P10-B does not change any of that — it makes the backend's
*operational* surface (errors, logs, audit trail, metrics, diagnostics) consistent,
sanitized, and traceable end-to-end, in preparation for Railway today and Google Cloud
later.

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
| `RUN_REVIEW_ERROR` | RESOURCE | no |
| `CLIENT_ERROR` (generic 4xx fallback) | VALIDATION | no |
| `INTERNAL_ERROR` | INTERNAL | no |
| `UNKNOWN` (defensive fallback only) | INTERNAL | no |

No historical alias renames were needed — `codeForStatus()` in `ApiExceptionHandler`
(mapping a bare `ResponseStatusException` status to a code) is unchanged from P10-A.

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
JDBC URLs with credentials, URLs with userinfo, Windows paths, `/tmp` and `/app` paths,
`key=value`/`key: value` secret pairs (password/secret/token/credential/authorization/
apikey), emails, common medical/image filenames (`.dcm`, `.nii`, `.png`, `.jpg`, ...),
and a length/non-printable-ratio heuristic for binary-looking content. Every result is
capped at 200 characters. See `SafeLogSanitizerTest` for the full coverage (synthetic
examples only, never a real secret).

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

For a call with nothing in MDC (a background/non-HTTP-request process), no header is
sent at all — the client never invents an id that would misleadingly look
request-scoped. Verified end-to-end with WireMock in `AiTracePropagationTest`.

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
`"multiplanar.run.completed"`, ...) are untouched — some already exactly match a catalog
name (`PROFESSIONAL_ACTIVATED`/`PROFESSIONAL_DEACTIVATED` in `AuthService`), and nothing
currently consuming those strings breaks. The catalog exists so *new* critical operations
have one place to draw a stable action name from, per the task's own instruction not to
force a breaking rename in this pass.

Audit events already carried (unchanged): `actorId` (technical), `action`, `entityId`
(technical — activation/deactivation already used `account.email()` as the technical
identifier since P10-A.2.1; this was not touched in P10-B since it is not a "new call
site" and changing it risks breaking existing audit queries by email), `traceId`,
`timestamp`, `outcome` (via metadata), sanitized `metadata`.

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
plus `databaseUnavailable`/`aiContractViolations` by code), `AiServiceClient`
(aiCallsTotal/Succeeded/Failed/duration in `execute`/`executeV2`, aiContractViolations on
a contract-violation exception or a `TraceIdConsistencyGuard` mismatch), `RunReviewService`
(reviewsSaved on a successful `saveReview`), `AuditService` (auditWriteFailures).

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

Unchanged from P10-A/pre-existing `AiServiceClient` logic, now documented explicitly:

| Condition | Code | Status |
|---|---|---|
| Timeout | `AI_MODULE_TIMEOUT` (v2) / generic timeout mapping (v1) | 504 |
| Connection refused / host unreachable | mapped to `ResponseStatusException`/`AiMultiplanarUpstreamException` | 502/503 depending on path |
| Upstream 5xx | `AI_MODULE_ERROR` / `UPSTREAM_UNAVAILABLE` | 502 |
| Upstream 4xx | passed through as the AI Module's own rejection | matches upstream |
| Malformed/unstructured JSON body on a v2 error | `AiMultiplanarV2ErrorCodeMapper.UNKNOWN` | 502 |
| Trace id mismatch (v2 body vs. header) | `AI_MULTIPLANAR_CONTRACT_VIOLATION` | 502 |

The AI Module's base URL is never included in any error message or diagnostics field
(verified by `SystemDiagnosticsServiceTest` and `SensitiveDataLeakRegressionTest`).

## 17. Database error classification (unchanged)

`DatabaseUnavailableException` → `DATABASE_UNAVAILABLE`, 503 — used when Postgres itself
is unreachable/misconfigured. A genuinely missing resource (a study, a run, a review)
still returns its own specific 404 code (`STUDY_NOT_FOUND`, `RUN_NOT_FOUND` via
`RunReviewException`, etc.) rather than being folded into the database-unavailable
bucket. No SQL, table/column name, JDBC driver name, JDBC URL, or DB username is ever
returned — confirmed by `SensitiveDataLeakRegressionTest` and the existing `AuditServiceTest`.

## 18. Tests

New suites (all passing, see §22):

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
- `AiTracePropagationTest` (WireMock) — every AI Module call carries the current
  X-Trace-Id, no header sent when MDC is empty, no `Authorization` header ever sent.
- `ObservabilityDiagnosticsTest` — sanitized shape, graceful degradation without metrics.
- `SensitiveDataLeakRegressionTest` — end-to-end sweep across `AuthFilter`,
  `ApiExceptionHandler` for a JWT-looking `Authorization` header, a JDBC-URL-bearing
  runtime exception, Windows/`/tmp` paths, an AI Module private URL, and a password sent
  in a request body/query string.

Existing suites updated only where the constructor surface genuinely grew (additive
`@Nullable` parameters): `SystemDiagnosticsServiceTest` (constructor now takes 9 params).
No test's *assertions* about existing behavior changed — only signatures.

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
`humanReviewRequired`, `notClinicalDiagnosis`. Verified via `git status`/`git diff`
scope (only `config/`, `config/error/`, `auth/AuthFilter.java`,
`auth/AdminAccountProtectedException.java`-adjacent files were touched, plus
`service/` audit/metrics/review/diagnostics files and `client/AiServiceClient.java`) and
by the full pre-existing test suite (351 tests from P10-A.2.2) staying green unchanged.

## 22. Rollback

Every change is additive or a narrowing of an existing surface, and there is no schema
migration:

- `ApiErrorWriter`/`ApiErrorCode`/`ApiErrorResponse`/`SafeLogSanitizer`/
  `OperationalMetricsService`/`AuditAction` are all new files — deleting them and
  reverting their few call sites restores the P10-A.2.2 behavior exactly.
- Every new constructor dependency added to an existing class (`AuthFilter`,
  `CorsResponseFilter`, `TraceIdFilter`, `ApiExceptionHandler`, `AuditService`,
  `AiServiceClient`, `RunReviewService`, `SystemDiagnosticsService`) is either
  `@Nullable` or has a backward-compatible delegating overload — a `git revert` of this
  commit needs no follow-up migration.
- No data was migrated, no table was altered, no environment variable is newly
  *required* (all new behavior activates automatically once `OperationalMetricsService`
  is on the classpath — there is no new required config).

## 23. Evidence of tests

```
$env:JAVA_HOME = "C:\Program Files\Eclipse Adoptium\jdk-21.0.11.10-hotspot"
$env:Path = "$env:JAVA_HOME\bin;$env:Path"
mvn clean test
```

**BUILD SUCCESS. Total tests: 414, Failures: 0, Errors: 0** (was 351 at P10-A.2.2; +63
new tests, 0 removed, 0 behavior-changing modifications to pre-existing test
assertions — only additive `@Nullable` constructor-signature updates in
`SystemDiagnosticsServiceTest`). Compiled with `--release 17` (unchanged `pom.xml`),
executed on Temurin 21 per the task's instruction (`JAVA_HOME` must point at a JDK 21
install — Mockito's inline mock maker cannot instrument classes under a JDK 25
`JAVA_HOME`, a pre-existing environment caveat carried over from P10-A.2.2, not new here).
