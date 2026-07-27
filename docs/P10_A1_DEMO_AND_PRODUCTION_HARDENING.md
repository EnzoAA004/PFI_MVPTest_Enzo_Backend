# P10-A.1 — Demo Mode & Production Configuration Hardening

Commit base: `9e8b72c25306cf78395598ff1a06b81168381989` (P10-A)
Repository: `EnzoAA004/PFI_MVPTest_Enzo_Backend`

**P10-A.2 addendum:** this document correctly closes the demo account, but blocking it
also removes the only path that ever produced an ADMIN account — see
`docs/P10_A2_ADMIN_BOOTSTRAP_AND_ACTIVATION.md` for the admin-bootstrap mechanism that
must be deployed together with this hardening, not after a gap in production.

## Risk discovered

A post-P10-A review found that P10-A's hardening had a gap: it closed the
`/api/auth/demo-doctor` seed endpoint *conditionally* (only when `SPRING_PROFILES_ACTIVE`
contained `production`/`prod`), but left several other paths to the same outcome — an
unauthenticated caller obtaining an ADMIN token — open:

1. `AuthFilter.PUBLIC_AUTH_PATHS` still listed `/api/auth/demo-doctor`
   **unconditionally**, so the endpoint was always network-reachable without a token; only
   `AuthService.seedDemoDoctor()`'s own profile check stood between an anonymous caller
   and a token, and only when the deployment actually set the production profile.
2. `AuthService.seedDemoDoctor()` **persists** a well-known account
   (`doctor.demo@pfi.local` / `Demo1234!` / `ADMIN,DOCTOR,REVIEWER`) to Postgres. Once
   that row exists, closing the seed *endpoint* does nothing to stop that account from
   authenticating through the **normal** login/refresh flow — those never checked for
   "is this the demo account and is demo mode allowed".
3. The production check only fired for `production`/`prod` profiles; if Railway is ever
   deployed without `SPRING_PROFILES_ACTIVE` set (the P10-A evidence doc already flagged
   this as an operational dependency), every one of these checks was silently inert.
4. `pfi.auth.enabled=false` fully disables `AuthFilter` — every endpoint becomes
   anonymous. Nothing in P10-A's `SecurityStartupValidator` checked this in production.
5. `pfi.auth.expose-dev-codes` defaulted to `true`, so 2FA/registration verification
   codes were returned in API responses by default.
6. `/api/ai/health`, `/api/ai/models`, and `/api/system/warmup` were anonymously public
   — `warmup` in particular triggers a real AI Module operation with no auth at all.

**Why blocking the endpoint alone was not enough:** endpoint-level gating and
account-level gating are two different controls. P10-A only had the first. A demo
account that is merely *dormant* (not deleted, not marked, not blocked) is still a live
credential as far as login/refresh are concerned — the fix has to key off the account's
identity at every place a token can be (re)issued, not just at the one place it is
first created.

## What changed

### Demo mode is now an explicit, default-off switch

- New `pfi.auth.demo-enabled` (`PFI_AUTH_DEMO_ENABLED`, default `false`).
- `AuthFilter.DEMO_DOCTOR_PATH` is only treated as public when
  `demoEnabled && !productionProfile` — otherwise the path falls through to the normal
  "authenticated" requirement (401 for anonymous callers), it does **not** stay silently
  reachable.
- `AuthService.seedDemoDoctor()` independently re-checks the same condition and returns
  404 ("No encontrado") regardless of how the request reached it — so even an
  authenticated-but-non-admin caller hitting the now-protected path when demo is
  disabled still gets refused at the service layer.
- `SecurityStartupValidator` fails production startup if `pfi.auth.demo-enabled=true`.

### The persisted demo account is blocked by identity, not by role

- `AuthService.DEMO_ACCOUNT_EMAIL = "doctor.demo@pfi.local"` is now the single source of
  truth for the identity check (not a role check — the row may already carry `ADMIN`).
- `login()`, `verify()` (2FA/registration challenge completion), and `refresh()` all
  reject this identity with a generic 401 (`"Credenciales inválidas"` /
  `"Código expirado o inválido"` / `"Refresh token inválido o revocado"` — same messages
  used for any other invalid attempt, so the response never confirms the account exists
  or that it is specifically the demo account being blocked).
- `refresh()` additionally **revokes** the offending refresh token before returning 401.
- On startup, `AuthService.revokeDemoSessionsIfDemoDisabled()` (`@PostConstruct`) proactively
  revokes any refresh tokens already issued to the demo account from a previous
  deployment, both in Postgres (`PostgresAuthStoreService.revokeRefreshTokensForEmail`,
  new — single-email `UPDATE ... WHERE email = ?`, no `DELETE`, no wildcard, other
  accounts' tokens are untouched) and in the in-memory fallback map.

### `pfi.auth.enabled` is now production-gated

`SecurityStartupValidator` fails production startup if `pfi.auth.enabled=false` — a
production deployment can no longer accidentally run with `AuthFilter` fully disabled.

### Verification codes are hidden by default

`pfi.auth.expose-dev-codes` (`PFI_AUTH_EXPOSE_DEV_CODES`) now defaults to `false`
(was `true`). `SecurityStartupValidator` fails production startup if it is `true`. The
code is still generated and validated internally exactly as before — only whether it is
echoed back in the API response changed.

### Minimal public surface

- `AuthFilter.PUBLIC_LIVENESS_PATHS` now contains **only** `GET /api/system/health`.
- `/api/ai/health` and `/api/ai/models` require authentication (any non-pending
  professional/ADMIN) — gated purely by `AuthFilter`, no extra role check needed since
  they carry no sensitive data beyond what any session-holder should see.
- `POST /api/system/warmup` requires ADMIN (`RoleAuthorizationService.requireAdmin`) —
  it triggers a real AI Module warmup call, not a read.
- `GET /api/ai/readiness` and `GET /api/ai/models/verify` are reclassified as ADMIN-only
  technical diagnostics (no documented professional consumer), closing the P10-A gap
  that had left them merely `AUTH`-only "for historical compatibility".

### CORS: no wildcard-by-default in production

- `pfi.cors.allowed-origin-patterns` now defaults to **empty** (was
  `https://*.vercel.app`) — a production deployment gets zero pattern-based origins
  unless explicitly configured.
- New `pfi.cors.allow-preview-patterns` (`PFI_CORS_ALLOW_PREVIEW_PATTERNS`, default
  `false`). `SecurityStartupValidator` fails production startup if any
  `pfi.cors.allowed-origin-patterns` are configured while this flag is not explicitly
  `true` — so a team that *does* want Vercel-preview wildcard support in production has
  to opt in explicitly, it can no longer happen by leaving a default in place.
- `SecurityStartupValidator` also now requires, in production: at least one exact origin
  in `pfi.cors.allowed-origins`, every exact origin must be `https://`, and none may be
  the literal `"*"`.
- The normal production origin remains `https://pfi-mvp-test-enzo-frontend.vercel.app`
  configured via `PFI_CORS_ALLOWED_ORIGINS` — this is a **configuration** default in
  `application.properties`, not a bypass hardcoded in Java (`CorsResponseFilter` itself
  contains no domain names).

### Constructors no longer bypass authorization when the collaborator is missing

`AiAuditController`, `AiBackendController`, `SystemController`, and
`AiModelSyncController` no longer have a single-argument (or null-defaulting) public
constructor that skips the `RoleAuthorizationService` call when the collaborator wasn't
wired. `RoleAuthorizationService` is now a required constructor argument and
`requireAdmin(...)` is called unconditionally in every gated method. All call sites in
tests now pass an explicit `Mockito.mock(RoleAuthorizationService.class)` (a real,
visible test double — not a hidden production-code bypass) when the test isn't
exercising authorization itself.

`AiRunReviewController` still has the old `if (authorizationService != null)` pattern
around its `requireProfessional` call — out of scope for this pass (it is a
professional-level check, not an admin-privileged one); tracked as a follow-up in the
baseline doc.

## Demo account details (documented, not embedded as a live secret)

The persisted demo identity is `doctor.demo@pfi.local`. Its password
(`Demo1234!`, only ever used by `AuthService.seedDemoDoctor()` when demo mode is
explicitly enabled) is not a production secret — it is a fixed, publicly-known
convenience credential for local development, which is exactly why it must never be
reachable in production regardless of whether it happens to be persisted.

## JWT secret rotation on rollout

**Deploying this change does not by itself invalidate any already-issued access token**
(access tokens are stateless and valid until their own `exp`, up to 1 hour by default).
To close out any session that may have been issued under the old, weaker posture
(including any demo-account session issued before this change), **rotate
`PFI_AUTH_JWT_SECRET` in Railway as part of this rollout**:

- Set `PFI_AUTH_JWT_SECRET` to a new, random value (32+ bytes, not the repo demo
  default) — this repo does not and must not change the secret from code.
- Every access token signed with the old secret fails `TokenService.verify()`
  immediately after rotation (signature mismatch) — **all users, not just the demo
  account, will need to log in again.**
- Refresh tokens are opaque random strings stored server-side (not JWTs), so rotating
  the JWT secret does not by itself revoke them; the demo-account-specific revocation
  (`revokeRefreshTokensForEmail`) is the mechanism that closes those out for the demo
  account specifically. Normal users' refresh tokens remain valid after rotation (by
  design — only their *access* token needs renewing).
- After rotating, verify `POST /api/auth/demo-doctor` returns 404 in the production
  environment (with `PFI_AUTH_DEMO_ENABLED` unset/`false`), and that a login/refresh
  attempt against the demo account (if reachable at all pre-migration) now returns 401.

## Variables required (Railway, values not included here)

```
SPRING_PROFILES_ACTIVE=production
PFI_AUTH_ENABLED=true
PFI_AUTH_DEMO_ENABLED=false
PFI_AUTH_EXPOSE_DEV_CODES=false
PFI_AUTH_JWT_SECRET=<new secure secret, 32+ bytes, rotated at rollout>
PFI_CORS_ALLOWED_ORIGINS=<exact Vercel URL, https://...>
PFI_CORS_ALLOWED_ORIGIN_PATTERNS=
PFI_CORS_ALLOW_PREVIEW_PATTERNS=false
PFI_AI_SERVICE_MULTIPLANAR_CONTRACT_VERSION=v2
```

Railway itself is **not** modified by this commit — this is documentation for whoever
configures the deployment next.

## Tests added

- `DemoModeAuthServiceTest` (9 tests): anonymous seed refused when demo disabled/never
  issues a token; seed permitted when demo enabled locally; seed still refused when
  demo-enabled=true but production profile is active; persisted demo account blocked on
  login/refresh when demo disabled (refresh additionally verified to revoke the token);
  persisted demo account works when demo enabled locally; a normal professional account
  is provably unaffected; startup revocation fires (and only fires) when demo is disabled.
- `SecurityStartupValidatorTest` extended from 6 to 14 tests: added auth-disabled,
  demo-enabled, expose-dev-codes, and the four CORS production scenarios (no HTTPS
  origin, literal wildcard, patterns without preview opt-in, patterns with explicit
  opt-in).
- `SecurityAuthorizationIntegrationTest` extended with two new nested groups:
  `PublicSurfaceMatrix` (7 tests: anonymous/pending/professional/admin ×
  health/models/warmup) and `ReadinessAndVerifyAreAdminOnly` (3 tests).

## Rollback strategy

Every change is additive or a narrowing of an existing surface, and none touches the
database schema beyond one new, purely additive method
(`PostgresAuthStoreService.revokeRefreshTokensForEmail`, which only issues an `UPDATE`
against the existing `auth_refresh_tokens` table — no migration, no new table/column).
A `git revert` of this commit restores the P10-A behavior with no data cleanup required.
If a production deployment needs demo mode temporarily for a specific reason, it must
never set `PFI_AUTH_DEMO_ENABLED=true` alongside `SPRING_PROFILES_ACTIVE=production` —
startup will refuse to start rather than accept that combination.

## Expected effect: current sessions will end

Rotating `PFI_AUTH_JWT_SECRET` (see above) means **every currently logged-in user,
including legitimate professionals and ADMIN accounts, will be signed out and must log
in again** the next time their access token is checked. This is intentional and
unavoidable — it is the only way to guarantee no session survives that was issued under
the pre-P10-A.1 posture. Refresh tokens for real accounts remain valid (so re-login can
happen via the normal refresh flow if the frontend calls it), but demo-account refresh
tokens are explicitly revoked and will not work.

## Frontend debt for P10-C (not fixed here)

This commit does not touch the frontend. The following must be addressed before this
posture is meaningful end-to-end:

- Remove any precompleted demo credentials from the login form.
- Hide/remove the "Entrar con doctor demo" button in production builds.
- Never render `devVerificationCode` from an auth response (it should already be absent
  once `PFI_AUTH_EXPOSE_DEV_CODES=false` in production, but the frontend must not assume
  it is present even in dev without checking).
- If a local demo flow is still wanted for frontend development, gate it behind an
  explicit Vite build-time variable (e.g. `VITE_ENABLE_DEMO_LOGIN`), not by assuming the
  backend endpoint is always reachable.

**The frontend has not been verified or fixed as part of this change.** Do not assume
production frontend behavior has already been corrected.
