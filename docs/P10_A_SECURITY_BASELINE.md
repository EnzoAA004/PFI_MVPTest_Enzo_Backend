# P10-A — Security Baseline

Commit base: `c3b088080ee6742a712062bd9300ad592cf253d4`
Superseded in part by **P10-A.1** (base `9e8b72c25306cf78395598ff1a06b81168381989`) —
see `docs/P10_A1_DEMO_AND_PRODUCTION_HARDENING.md`. Rows below marked **(P10-A.1)** were
changed after the initial P10-A pass; this file has been updated in place rather than
kept as two divergent documents.

Further superseded by **P10-A.2** (admin bootstrap + institutional activation, see
`docs/P10_A2_ADMIN_BOOTSTRAP_AND_ACTIVATION.md`) and **P10-A.2.1** (base
`cd500255a58cec0ade91cd609a9f69627e66452c`): `AuthFilter` now revalidates the caller's
*persisted* account state on every protected production request (via
`AuthAccountStateService`) instead of trusting only the JWT's `roles` claim — a
deactivated account's still-valid access token is rejected on the very next request.
The legacy `/approval` endpoint now enforces the exact same rules as `/activation`
(same domain operation, same last-ADMIN protection, same fail-closed persistence).
Rows below marked **(P10-A.2.1)** reflect this.

## 0. Architecture reality check (audit finding #1)

This backend does **not** use Spring Security / `SecurityFilterChain` / `@PreAuthorize`.
Authentication and authorization are implemented as a custom stack:

- `ar.edu.uade.pfi.backend.auth.TokenService` — hand-rolled HS256 JWT issuance/verification.
- `ar.edu.uade.pfi.backend.auth.AuthFilter` — a `OncePerRequestFilter` that is the single
  authentication gate for every request (fail-closed: unlisted paths require a valid
  Bearer token).
- `ar.edu.uade.pfi.backend.auth.RoleAuthorizationService` — imperative role checks
  (`requireAdmin`, `requireProfessional`) called explicitly from controllers, reading
  `TokenService.Claims` off a request attribute set by `AuthFilter`.

P10-A hardens this **existing** architecture rather than introducing Spring Security,
because swapping the security stack is a large, high-risk rewrite that the task's own
regression requirement ("todos los tests actuales continuarán aprobados", no changes to
the multiplanar contract) argues against. Everywhere the task text says
"SecurityFilterChain" / "`@PreAuthorize`", read it as "`AuthFilter`" /
"`RoleAuthorizationService.requireAdmin/requireProfessional`" — the equivalent
fail-closed mechanisms in this codebase.

## 1. Real roles found in the repository

No new role names were invented. Roles as found in `DoctorAccount`, `TokenService`,
`AuthFilter`, `RoleAuthorizationService`:

- `ADMIN`
- `PENDING_APPROVAL` (assigned at registration; cleared to `DOCTOR`+`REVIEWER` on approval)
- `DOCTOR` (the real "professional" role)
- `REVIEWER` (secondary professional role, granted together with `DOCTOR` on approval)

`RoleAuthorizationService.requireProfessional` accepts `REVIEWER`, `DOCTOR`, or `ADMIN`.
`RoleAuthorizationService.requireAdmin` accepts only `ADMIN`.

## 2. Endpoint authorization matrix

Auth column: `PUBLIC` = no token required. `AUTH` = any valid, non-`PENDING_APPROVAL`
token. `PENDING+AUTH` = valid token, `PENDING_APPROVAL` explicitly allowed. `ADMIN` =
valid token with `ADMIN` role (`RoleAuthorizationService.requireAdmin`).
`PROFESSIONAL` = valid token with `DOCTOR`/`REVIEWER`/`ADMIN`
(`RoleAuthorizationService.requireProfessional`).

| Method | Route | Auth | Roles allowed | Data exposed | Justification | Tests |
|---|---|---|---|---|---|---|
| POST | /api/auth/register | PUBLIC | anonymous | challenge id, dev-only code | must be reachable before any account exists | AuthController/AuthService tests (pre-existing) |
| POST | /api/auth/verify-registration | PUBLIC | anonymous | access+refresh token | completes registration | pre-existing |
| POST | /api/auth/login | PUBLIC | anonymous | token or challenge | credential exchange | pre-existing |
| POST | /api/auth/verify-login | PUBLIC | anonymous | access+refresh token | 2FA completion | pre-existing |
| POST | /api/auth/refresh | PUBLIC | anonymous | new access+refresh token | session renewal, refresh token itself is the credential | pre-existing |
| POST | /api/auth/demo-doctor | **(P10-A.1)** PUBLIC only when `pfi.auth.demo-enabled=true` AND no `production`/`prod` profile; otherwise treated as any other protected route (401 anonymous) | anonymous, only when demo effectively enabled | ADMIN-privileged token | default is now `PFI_AUTH_DEMO_ENABLED=false`; service layer (`AuthService.seedDemoDoctor`) also refuses with 404 regardless of how the request reached it | `DemoModeAuthServiceTest`, `SecurityStartupValidatorTest` |
| POST | /api/auth/logout | PUBLIC | anonymous | ok flag | must work even with an expired access token | pre-existing |
| GET | /api/auth/me | PENDING+AUTH | any authenticated | own profile | pending users must see their own approval status | `AuthFilter` PENDING_ALLOWED_PATHS |
| PATCH | /api/auth/settings | PENDING+AUTH | any authenticated | own profile | pending users can complete 2FA/onboarding prefs | `AuthFilter` PENDING_ALLOWED_PATHS |
| GET | /api/auth/admin/professionals | AUTH → ADMIN (service-level) | ADMIN | professional roster (no passwordHash) | approval workflow | `AuthService.requireAdmin` |
| PATCH | /api/auth/admin/professionals/approval | **(P10-A.2.1)** ADMIN (`RoleAuthorizationService`, mandatory dependency) | ADMIN | approval result (`UserResponse`, kept for frontend compat) | **legacy/deprecated**, kept only because the current frontend still calls it; now delegates to the exact same `AuthService.setProfessionalActivation` domain operation as `/activation` — same last-ADMIN protection, demo blocking, fail-closed persistence, session revocation | `ApprovalEndpointControllerTest`, `ProfessionalActivationIntegrationTest` |
| PATCH | /api/auth/admin/professionals/activation | ADMIN (`RoleAuthorizationService`, mandatory dependency) | ADMIN | institutional activation result (no secrets) | production substitute for email verification (no real email provider); unknown fields (`roles`/`admin`/`password`/etc) explicitly rejected with 400 against the raw request body **(P10-A.2.1: no longer relies on a Jackson annotation alone — see §10 note below)**; never grants ADMIN; last-ADMIN-protected on deactivation | `ProfessionalActivationControllerTest`, `ProfessionalActivationRealObjectMapperTest`, `ProfessionalActivationIntegrationTest`, `LastAdminProtectionTest` |
| GET | /api/system/health | PUBLIC | anonymous | `{"status":"ok"}` only | minimal liveness, new in P10-A | `SecurityAuthorizationIntegrationTest` (indirect) |
| GET | /api/system/diagnostics | ADMIN | ADMIN | AI Module/db/auth diagnostics (no secrets, no AI Module URL) | admin-only troubleshooting | `RoleAuthorizationControllerTest`, `SecurityAuthorizationIntegrationTest.Admin` |
| POST | /api/system/warmup | **(P10-A.1)** ADMIN | ADMIN | warmup summary | was PUBLIC; now `RoleAuthorizationService.requireAdmin` because it triggers an AI Module operation, not a read | `SecurityAuthorizationIntegrationTest.PublicSurfaceMatrix` |
| GET | /api/ai/health, /api/ai/models | **(P10-A.1)** AUTH | professional/ADMIN | AI Module proxy passthrough | removed from `AuthFilter.PUBLIC_LIVENESS_PATHS`; use `/api/system/health` for anonymous liveness instead | `SecurityAuthorizationIntegrationTest.PublicSurfaceMatrix` |
| GET | /api/ai/readiness, /api/ai/models/verify | **(P10-A.1)** ADMIN | ADMIN | AI Module proxy passthrough | reclassified as technical diagnostics with no documented professional consumer; was `AUTH`-only, now `RoleAuthorizationService.requireAdmin` | `SecurityAuthorizationIntegrationTest.ReadinessAndVerifyAreAdminOnly` |
| POST | /api/ai/models/sync | ADMIN | ADMIN | sync result | changes served model artifacts | `RoleAuthorizationControllerTest` |
| GET/POST | /api/ai/audit, /api/ai/audit-events | ADMIN (hardened in P10-A) | ADMIN | audit trail across all users | system-wide audit search is an admin capability, not "propia" | new: `SecurityAuthorizationIntegrationTest` pattern (see risks — no dedicated controller test yet) |
| GET | /api/studies, /api/studies/{caseId}, /api/studies/{caseId}/runs | AUTH | professional/ADMIN | de-identified worklist/detail | clinical/academic worklist | `SecurityAuthorizationIntegrationTest.ApprovedProfessional/Admin` |
| PUT | /api/studies/{caseId}/metadata | PROFESSIONAL | professional/ADMIN | updated metadata | explicit `requireProfessional` call (defense in depth on top of `AuthFilter`) | pre-existing |
| POST | /api/studies/demo, GET /api/studies/demo-review | AUTH | professional/ADMIN | synthetic demo data only | demo fixtures, gated behind `requireDemoEnabled` | pre-existing |
| GET | /api/subjects/{subjectRef}/history | AUTH | professional/ADMIN | de-identified longitudinal history | clinical/academic | pre-existing |
| POST | /api/ai/inputs | AUTH | professional/ADMIN | upload ack | pending users blocked at `AuthFilter` | `SecurityAuthorizationIntegrationTest` (asset/anon coverage; dedicated input-upload role test is a documented gap) |
| POST | /api/ai/multiplanar/run | AUTH | professional/ADMIN | canonical multiplanar result (public presenter) | core inference entrypoint | `SecurityAuthorizationIntegrationTest.Anonymous/PendingApproval/ApprovedProfessional` |
| GET | /api/ai/assets/{runId}/{plane}/{assetName} | AUTH | professional/ADMIN | durable asset bytes (allow-listed names only) | `RunAssetSnapshotService` allow-list unchanged | `SecurityAuthorizationIntegrationTest.Anonymous.assetRequiresAuthentication`, pre-existing `AiAssetDurableProxyControllerTest`/`AiAssetProxyControllerTest` |
| POST/PUT/GET | /api/ai/runs/{id}/review, /api/ai/review/** | AUTH (some also `requireProfessional`) | professional/ADMIN | review state, measurements | reviewer workflow | pre-existing `AiRunReviewControllerTest` |
| GET | /api/ai/pipeline/schema, /api/ai/evaluation/** | AUTH | professional/ADMIN | contract/evaluation metadata | technical diagnostics for professionals | pre-existing |
| GET | /api/ai/completion, /api/ai/roadmap | AUTH | professional/ADMIN | internal roadmap/completion status | non-clinical, informational | pre-existing |

Full route list matches `@RequestMapping`/`@GetMapping`/... across `src/main/java/.../controller/**` and `AuthController`; anything not explicitly listed as `PUBLIC` above falls through to `anyRequest` semantics in `AuthFilter` (deny unless authenticated).

## 3. Public surface (fail-closed, explicit list only)

`AuthFilter.PUBLIC_AUTH_PATHS` ∪ `AuthFilter.PUBLIC_LIVENESS_PATHS` ∪ `OPTIONS` requests.
**Hardening**: the previous fallback `return !path.startsWith("/api/")` (implicitly public
for anything outside `/api/**`) was removed — there is nothing served outside `/api/**`
in this application, so this closes a wildcard that the task explicitly forbids.

## 3b. Per-request account-state revalidation (P10-A.2.1)

Previously, `AuthFilter` only checked the JWT's signature/expiry and then trusted its
`roles` claim for the rest of the request's lifetime — meaning a deactivated account's
already-issued token kept working until it naturally expired. As of P10-A.2.1, after
JWT verification succeeds, `AuthFilter` calls `AuthAccountStateService.resolve(...)`:

- **Outside production**: the JWT's claims are trusted directly (no Postgres read) —
  preserves all existing dev/test behavior unchanged.
- **In production**: Postgres is queried via
  `PostgresAuthStoreService.findByEmailForAuthorization` (fail-closed: never swallows
  an exception) for every protected request. The account's *current* `id`/`email`/
  `fullName`/`roles` become the request's effective claims — not the token's. Outcomes:
  - account missing, or `id` doesn't match the token's `subject` → 401 `AUTHENTICATION_REQUIRED`
  - the demo account → 401 `AUTHENTICATION_REQUIRED`
  - Postgres disabled or the query fails → 503 `AUTH_STATE_UNAVAILABLE` (never falls
    back to trusting the token in production)
  - otherwise → request proceeds with the persisted roles, and `PENDING_APPROVAL`/no
    recognized role is still restricted to `/me`+`/settings` exactly as before

Proven end-to-end against real Postgres in
`AccountStateImmediateInvalidationIntegrationTest`.

## 4. PENDING_APPROVAL surface

`AuthFilter.PENDING_ALLOWED_PATHS` = `/api/auth/me`, `/api/auth/settings` (plus the
always-public auth endpoints, notably `/api/auth/logout`). Everything else returns 403
`ACCESS_DENIED` for a `PENDING_APPROVAL` token. This already matched the task's target
policy before P10-A; no change was needed there beyond standardizing the error body.

## 4b. Unknown-field rejection on ProfessionalActivationRequest (P10-A.2.1 §10 note)

P10-A.2 relied on `@JsonIgnoreProperties(ignoreUnknown = false)` on the
`ProfessionalActivationRequest` record, verified only against a hand-built `new
ObjectMapper()` in a standalone `MockMvc` test. A P10-A.2.1 `@WebMvcTest` against the
*real* Spring Boot-managed `ObjectMapper` (`ProfessionalActivationRealObjectMapperTest`)
found that annotation did **not** actually reject unknown fields under the app's real
Jackson configuration — extra fields were silently dropped and the request bound
successfully. `AuthController.updateProfessionalActivation` was changed to bind the
body as a raw `JsonNode` and explicitly reject any field outside `{email, activated}`
before constructing the DTO at all, independent of any Jackson global/record-introspection
behavior. This is now proven against the real context, not just a standalone one.

## 5. Known gaps (see `P10_A_SECURITY_EVIDENCE.md` → "Limitaciones que NO deben sobreafirmarse")

- ~~`/api/ai/readiness` and `/api/ai/models/verify` are `AUTH`-only~~ — closed in P10-A.1,
  both are now ADMIN-only.
- No per-controller MockMvc test was added for `/api/ai/inputs` role enforcement
  specifically (covered indirectly through `AuthFilter`'s blanket gate, exercised in
  `SecurityAuthorizationIntegrationTest`).
- No IDOR/ownership model exists for studies/runs by professional — see the evidence
  document's explicit non-claim about multi-tenancy.
- `AiRunReviewController` still has a `if (authorizationService != null)` null-bypass
  around its `requireProfessional` call (P10-A.1 §10 only mandated removing this pattern
  from `AiAuditController`/`AiBackendController`/`SystemController`/`AiModelSyncController`
  — the administrative ones). Left as a follow-up since it is a professional-level check,
  not an admin one, and touching it would require rewiring `AiRunReviewControllerTest`.
