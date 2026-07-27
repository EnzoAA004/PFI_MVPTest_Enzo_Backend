# P10-A — Security Evidence

Commit base: `c3b088080ee6742a712062bd9300ad592cf253d4`
Repository: `EnzoAA004/PFI_MVPTest_Enzo_Backend`

**P10-A.1 addendum (base `9e8b72c25306cf78395598ff1a06b81168381989`):** a post-review pass
found that P10-A's production-startup gate was not sufficient on its own — see
`docs/P10_A1_DEMO_AND_PRODUCTION_HARDENING.md` for the blocking risks found and closed
(demo endpoint still unconditionally public, persisted demo account still usable via
login/refresh even with the endpoint closed, `pfi.auth.enabled=false` had no production
guard, dev verification codes defaulted to exposed, and `/api/ai/health`/`/api/ai/models`/
`/api/system/warmup` were still anonymously public). **Do not deploy on P10-A alone.**

## Endpoint matrix

See `docs/P10_A_SECURITY_BASELINE.md`.

## Real roles found

`ADMIN`, `PENDING_APPROVAL`, `DOCTOR`, `REVIEWER` (see baseline §1). No role names were
invented; `RoleAuthorizationService` and `AuthFilter` already used these before P10-A.

## Changes made

1. **JWT hardening** (`TokenService`):
   - Missing/blank subject now rejects the token (previously accepted `sub:""`).
   - Missing/malformed `roles` claim now yields **no** authorities (`List.of()`) instead
     of silently defaulting to `DOCTOR` — a signed-but-incomplete token could previously
     be granted a professional role it never explicitly claimed.
   - Null/blank token and empty JWT segments are rejected before any parsing.
   - Confirmed (documented, no code change needed): the verifier always recomputes
     HMAC-SHA256 itself and never branches on the token's own `alg` header, so
     algorithm-confusion (`alg:none`) is not possible.
2. **Production startup gate** (`SecurityStartupValidator`, new): when
   `SPRING_PROFILES_ACTIVE` contains `production`/`prod`, refuses to finish starting if
   `pfi.auth.jwt-secret` is blank, is still the repo's demo default
   (`pfi-demo-change-me-2026`), or is under 32 UTF-8 bytes. No-ops for every other
   profile (default/dev/test), so `mvn test` is unaffected.
3. **Removed an unauthenticated admin-token backdoor**: `POST /api/auth/demo-doctor`
   (`AuthService.seedDemoDoctor`) issued a fully-approved `ADMIN,DOCTOR,REVIEWER` token
   with **no credential check whatsoever**. It now returns 404 when the production
   profile is active; unchanged in dev/test.
4. **Removed the AuthFilter public-path wildcard**: `!path.startsWith("/api/")` used to
   make anything outside `/api/**` implicitly public. There is nothing served outside
   `/api/**` in this app (verified via `grep` across all `@RequestMapping`), so this was
   a dead-but-real wildcard the task explicitly asked to close.
5. **Standardized 401/403 bodies**: `AuthFilter` now writes
   `{"status":"error","code":"AUTHENTICATION_REQUIRED"|"ACCESS_DENIED","message":...,"traceId":...,"timestamp":...}`
   directly; `ApiExceptionHandler` maps `UNAUTHORIZED`→`AUTHENTICATION_REQUIRED` and
   `FORBIDDEN`→`ACCESS_DENIED` (was `UNAUTHORIZED`/`FORBIDDEN`) so `RoleAuthorizationService`
   403s use the same code/message. `Cache-Control: no-store` is set on these responses.
6. **ADMIN-gated system-wide audit reads**: `AiAuditController` (`GET
   /api/ai/audit-events`) and `AiBackendController`'s `GET`/`POST /api/ai/audit` now call
   `RoleAuthorizationService.requireAdmin` (both keep a null-safe constructor overload for
   existing tests that construct them without a `RoleAuthorizationService`).
7. **New minimal liveness**: `GET /api/system/health` → `{"status":"ok"}`, public, no
   AI Module/DB/secret details — matches §8A of the task exactly. Existing
   `/api/ai/health` (an AI-Module connectivity proxy, pre-existing, left unchanged) is
   documented as a distinct thing in the baseline doc.
8. **CORS consolidation** (finding: two overlapping filters existed —
   `CorsConfig`'s Spring `CorsFilter` and `CorsResponseFilter` both set
   `Access-Control-*` headers, with different hardcoded origin defaults and different
   `allowCredentials` values, risking duplicate/conflicting headers):
   - Deleted `CorsConfig.java`; `CorsResponseFilter` is now the single CORS mechanism.
   - Removed hardcoded production domain matching from Java
     (`origin.startsWith("https://pfi-mvp-test-enzo-frontend")...`); replaced with a
     configurable `pfi.cors.allowed-origin-patterns` (`PFI_CORS_ALLOWED_ORIGIN_PATTERNS`)
     glob list (`*` wildcard only), defaulting in `application.properties` to
     `https://*.vercel.app` so Vercel preview deployments keep working without any code
     change — but this default lives in configuration, not Java, and is fully overridable.
   - `"*"` is now explicitly filtered out of both the exact-origin and pattern lists
     (credentials are allowed, so a literal wildcard must never be honored).
   - OPTIONS preflight now explicitly rejects (403) an unrecognized `Origin` or an
     `Access-Control-Request-Method` outside the allow-listed method set, instead of
     silently returning 204 regardless of origin.
9. **Security response headers** (`SecurityHeadersFilter`, new): `X-Content-Type-Options:
   nosniff`, `Referrer-Policy: no-referrer`, `X-Frame-Options: DENY`,
   `Content-Security-Policy: default-src 'none'; frame-ancestors 'none'` on every
   response; `Cache-Control: no-store` additionally on `/api/auth/**` and
   `/api/system/diagnostics`.
10. **Error message alignment**: `RoleAuthorizationService`'s 403 message changed from
    "Rol insuficiente" to the task's exact text "No tiene permisos para realizar esta
    operación." — this required updating two pre-existing test assertions
    (`RoleAuthorizationControllerTest`, `AiRunReviewControllerTest`) that checked the old
    literal string; behavior (403 on insufficient role) is unchanged, only the message.

## Risks mitigated

- Unauthenticated admin-token issuance (`/api/auth/demo-doctor`) in a would-be
  production deployment.
- Silent privilege grant (`DOCTOR` default) for tokens with a missing/malformed `roles`
  claim.
- A production deploy silently running on the repository's demo JWT secret forever
  (nothing previously would have stopped that).
- Inconsistent/duplicated CORS header logic that could either reject legitimate
  cross-origin requests intermittently or (worse) accept an unintended origin due to two
  filters disagreeing.
- Unrestricted read of the global audit trail (`traceId`/`entityId` search across every
  case) by any authenticated non-admin professional.
- Inconsistent 401/403 bodies made it harder for the frontend (and future automated
  checks) to distinguish "not logged in" from "logged in but not allowed" reliably.

## Risks pending (explicitly not addressed in this pass)

- `/api/ai/readiness` and `/api/ai/models/verify` remain `AUTH`-only rather than
  `ADMIN`-only, despite being diagnostic-flavored — left as-is to avoid scope creep
  without an explicit instruction; flagged for a follow-up P10 iteration.
- No dedicated MockMvc test exists yet for `AiAuditController`'s new ADMIN gate or for
  `/api/ai/inputs` role enforcement in isolation (both are exercised indirectly through
  `AuthFilter`'s blanket gate in `SecurityAuthorizationIntegrationTest`, but not with a
  role-matrix test of their own).
- `PasswordHasher`/registration input validation for empty passwords was not
  re-audited line-by-line in this pass; `RegisterRequest` bean-validation annotations
  were not modified.
- Rate limiting / brute-force protection on `/api/auth/login` was not added — noted as
  a gap for the "rate limiting distribuido y WAF" item below.

## Rollback strategy

Every change here is additive or a narrowing of an existing surface:

- `SecurityStartupValidator` only activates on an explicit `production`/`prod` profile;
  removing the `@Component` (or reverting the file) restores the old unconditional
  startup.
- `AuthFilter`, `RoleAuthorizationService`, `ApiExceptionHandler`, `CorsResponseFilter`,
  `AiAuditController`, `AiBackendController` changes are all reachable via a single
  `git revert` of this commit with no data migration involved (no schema changes were
  made).
- `CorsConfig.java` deletion is recoverable via `git revert`; no other code referenced
  the bean it defined (verified — zero references in `src/test`).

## Variables required

| Variable | Purpose | Required in production? |
|---|---|---|
| `PFI_AUTH_JWT_SECRET` (`pfi.auth.jwt-secret`) | HS256 signing key | **Yes** — startup now fails without a real one when `SPRING_PROFILES_ACTIVE=production` |
| `SPRING_PROFILES_ACTIVE=production` | Activates the production startup gate | Recommended — without it, the JWT secret validation is a no-op (documented risk) |
| `PFI_CORS_ALLOWED_ORIGINS` (`pfi.cors.allowed-origins`) | Exact allowed CORS origins | Yes, should include the real Vercel deployment URL |
| `PFI_CORS_ALLOWED_ORIGIN_PATTERNS` (`pfi.cors.allowed-origin-patterns`) | Glob CORS origin patterns (Vercel previews) | Optional; defaults to `https://*.vercel.app` |
| `PFI_AI_SERVICE_MULTIPLANAR_CONTRACT_VERSION` | v1/v2 feature flag | Unchanged by P10-A |
| `PFI_AI_SERVICE_URL` | AI Module base URL | Unchanged; never logged/returned (pre-existing behavior, re-verified) |

No secret values were added to `application.properties`, tests, fixtures, or README —
only the existing pre-P10-A demo default (already committed before this task) and
`${ENV_VAR:default}` placeholders.

## Tests executed

New test classes:

- `SecurityAuthorizationIntegrationTest` (7 nested groups, 25 test methods): anonymous
  401s, `PENDING_APPROVAL` 403s, approved-professional access, ADMIN access, JWT edge
  cases (expired/tampered/malformed/no-subject/no-authorities/empty-bearer), CORS
  (allowed/rejected/preflight/wildcard-pattern), and 401/403 body-shape/no-leak checks.
- `SecurityStartupValidatorTest` (6 tests): non-production never blocks; production
  blocks on blank/demo-default/weak secret; production allows a strong secret; `prod`
  alias also treated as production.
- `CorsResponseFilterTest` (3 tests): origin trimming/normalization, empty-config-is-safe,
  wildcard is never honored even if configured.

Pre-existing tests updated (message text only, not behavior):
`RoleAuthorizationControllerTest`, `AiRunReviewControllerTest`.

## Result of build

```
$env:JAVA_HOME = "C:\Program Files\Eclipse Adoptium\jdk-21.0.11.10-hotspot"
$env:Path = "$env:JAVA_HOME\bin;$env:Path"
mvn clean test
```

BUILD SUCCESS. **Total tests: 223, Failures: 0, Errors: 0** (was 189 before P10-A; +34
new tests, 0 removed). Compiled with `--release 17` (unchanged `pom.xml`), executed on
Temurin 21 per the task's instruction (not Java 25).

## Limitaciones que NO deben sobreafirmarse

- **No existe multi-tenancy / ownership por profesional.** Studies/runs are not scoped
  to the professional that created them; any approved `DOCTOR`/`REVIEWER` can read any
  case in the de-identified worklist. This was true before P10-A and remains true — no
  ownership model was invented. Isolation by organization/center, if ever required, is
  a separate, larger data-model change (tracked as a debt item, not implemented here).
- TLS terminates at Railway/Cloudflare/Vercel today; this backend does not terminate
  TLS itself.
- The AI Module is temporarily reachable behind a Cloudflare tunnel; its URL is never
  returned to clients (verified in `SystemDiagnosticsService`), but the tunnel itself is
  an operational, not application-level, control.
- Distributed rate limiting and a WAF are not implemented here; they are expected to
  land with GCP/Cloud Armor in a later phase.
- This tool is not a clinical diagnostic device; human review remains mandatory
  (`humanReviewRequired`/`notClinicalDiagnosis` flags are preserved unchanged throughout).
- The `SecurityStartupValidator` production gate only fires when
  `SPRING_PROFILES_ACTIVE` is explicitly set to `production`/`prod`. If Railway's actual
  deployment does not set this, the gate is inert — this must be confirmed/configured
  operationally, it is not automatic just by deploying to Railway.
