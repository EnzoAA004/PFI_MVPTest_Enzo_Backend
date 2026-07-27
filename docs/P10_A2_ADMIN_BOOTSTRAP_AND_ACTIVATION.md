# P10-A.2 — Admin Bootstrap & Institutional Activation

Commit base: `bb301b48101b51789745e48a80abc0f87715e12e` (P10-A.1)
Repository: `EnzoAA004/PFI_MVPTest_Enzo_Backend`

P10-A.2.2 (commit base `69f04cfc8f75ed67c4dc2f3ed8607a84f4d26575`) closes the three gaps
left after P10-A.2.1: ADMIN accounts are now fully protected from the professional
activation/approval flow, `verified`/`approved` are authoritative for authorization (not
just `roles`), and bootstrap-admin detection requires an exact, active ADMIN. See the
dedicated sections below for each.

## Risk of administrative lockout

P10-A.1 correctly closed `/api/auth/demo-doctor` and blocked the persisted demo account
in production — but `DoctorAccount.approve(true)` (the only path that turns a
`PENDING_APPROVAL` registration into a real account) only ever grants `DOCTOR,REVIEWER`.
The demo account was the *only* code path that ever produced an `ADMIN` account. The
consequence: deploy with

```
SPRING_PROFILES_ACTIVE=production
PFI_AUTH_DEMO_ENABLED=false
```

and rotate `PFI_AUTH_JWT_SECRET` (as P10-A.1 requires), and the system has **zero**
accounts capable of approving professionals, reading diagnostics, running warmup, or
reading audit trails — a real, betwen-releases-only-visible lockout, not a theoretical
one. P10-A.2 closes this by giving production a deliberate, one-time, auditable way to
create exactly one real ADMIN.

## Why the email problem also matters here

There is no real email provider in this codebase — `AuthService.createChallenge`
generates a code and returns it in `devVerificationCode` only when
`pfi.auth.expose-dev-codes=true`, which P10-A.1 made default to `false` and disallowed
in production entirely. So a newly self-registered professional in production has no
way to complete email verification themselves. **Institutional manual activation**
(the new `PATCH /api/auth/admin/professionals/activation` endpoint) is the
production-usable substitute: an ADMIN vouches for the account directly, skipping the
challenge flow, rather than the system pretending an email was sent and confirmed.

**This is explicitly not the same guarantee as email verification.** No claim is made
that the professional's email or license was externally validated — only that an
authenticated institutional ADMIN attested to it. That distinction is intentional and
should be preserved in any future UI copy.

## How bootstrap works

`AdminBootstrapService` (`ar.edu.uade.pfi.backend.auth.AdminBootstrapService`) is a
plain `@Component`, not a controller — **there is no HTTP endpoint for bootstrap, ever**.
It runs once via `@PostConstruct`, and only when the active Spring profile is
`production`/`prod`; every other profile is a complete no-op (so `mvn test` never
depends on any `PFI_AUTH_BOOTSTRAP_ADMIN_*` variable).

On a production start:

1. **An ADMIN already exists** (`PostgresAuthStoreService.hasNonDemoAdmin(DEMO_ACCOUNT_EMAIL)`
   is true) → start normally. The existing account's id, password hash, roles, name, and
   license are never touched. As of P10-A.2.2, "exists" means an **exact, active** ADMIN:
   `findNonDemoAdmin` uses `roles LIKE '%ADMIN%'` only as an index-friendly SQL
   pre-filter, then re-checks every candidate row in Java against
   `verified = true`, `approved = true`, and the exact, comma-split role list containing
   `"ADMIN"` — a `SUPERADMIN`/`PENDING_ADMIN` role, or an unverified/unapproved ADMIN row,
   no longer counts as "an ADMIN already exists", so bootstrap correctly runs (or
   correctly fails closed if disabled) in those cases instead of silently skipping.
2. **No ADMIN exists and bootstrap is disabled** (`PFI_AUTH_BOOTSTRAP_ADMIN_ENABLED`
   unset/`false`) → refuse to start with the sanitized message *"No existe un
   administrador productivo configurado."* — no email, variable name, or secret value in
   the exception.
3. **No ADMIN exists and bootstrap is enabled** → validate every
   `PFI_AUTH_BOOTSTRAP_ADMIN_*` variable (see below), create the account, persist it,
   **re-read it back from Postgres** to confirm it actually landed and carries `ADMIN`,
   and only then let startup continue. Any failure at any of these steps — including a
   Postgres connection failure — is a startup failure, not a silent fallback. No access
   token or refresh token is ever issued during bootstrap.

Validation, all sanitized (never logs the email, password, or JWT secret values):
syntactically valid email; not the demo email; password ≥16 chars; password not
`Demo1234!`; password not equal to `PFI_AUTH_JWT_SECRET`; full name, license number, and
institution all non-blank.

## Idempotency and concurrency strategy

The created account gets `roles = ADMIN,DOCTOR,REVIEWER`, `verified = true`,
`approved = true`. Concurrency: `PostgresAuthStoreService.createBootstrapAdmin` issues
`INSERT ... ON CONFLICT (email) DO NOTHING` against the existing `UNIQUE(email)`
constraint — if two instances race to bootstrap the same email, exactly one `INSERT`
succeeds and the other is a no-op (not an error, not a second row). Either way, the
caller always re-reads the row by email afterwards, so both instances converge on the
identical persisted identity (same id, same password hash) without needing a
distributed lock. On every subsequent start, step 1 above (`hasNonDemoAdmin`) short-circuits
before `createBootstrapAdmin` is ever called again — verified in
`AdminBootstrapPostgresIntegrationTest.bootstrapPersistsAdminAndSecondStartupIsIdempotent`
(same id, same hash, exactly one row after two `bootstrap()` calls).

## P10-A.2.2: ADMIN accounts are never touched by the professional flow

There is no dedicated "edit ADMIN" endpoint. Before P10-A.2.2, the only place an ADMIN's
status could change was professional deactivation
(`PATCH .../activation` with `activated=false`) — guarded only by a last-ADMIN-standing
check. That left a real gap: `activate(account)` unconditionally overwrote
`roles=DOCTOR,REVIEWER`, so calling `activated=true` on an account that already carried
`ADMIN` silently **stripped its ADMIN role** without ever consulting the last-ADMIN
check (which only ran on the `activated=false` branch).

`AuthService.setProfessionalActivation` now checks `account.roles().contains("ADMIN")`
immediately after loading the target account, **before** branching into
`activate()`/`deactivate()`, and rejects the request outright — in both directions —
with `AdminAccountProtectedException` (`409 ADMIN_ACCOUNT_PROTECTED`, message *"Las
cuentas administrativas no pueden modificarse mediante el flujo de profesionales."*).
This applies identically to `/activation` and the legacy `/approval` endpoint, since both
delegate to the same method. An ADMIN account's roles, `approved`/`verified` flags,
password hash, and everything else are left completely untouched by this flow — managing
an administrator requires a separate, dedicated flow that does not exist yet.

The older last-ADMIN-standing check (`AuthService.isLastNonDemoAdmin`/
`countOtherNonDemoAdmins`, `LastAdminProtectionException`, `409 LAST_ADMIN_PROTECTION`)
is kept in the code as defense-in-depth for any other future flow that might deactivate
an ADMIN — but it is now unreachable from the professional-activation path, since the
ADMIN guard above always fires first. See `LastAdminProtectionTest` (updated for
P10-A.2.2) and `ProfessionalActivationIntegrationTest`'s admin-protection tests.

## Institutional activation endpoint

`PATCH /api/auth/admin/professionals/activation` — ADMIN-only
(`RoleAuthorizationService.requireAdmin`, a mandatory constructor dependency on
`AuthController`, no null-check bypass). Request body accepts **only** `email` and
`activated`; the DTO (`ProfessionalActivationRequest`) is annotated
`@JsonIgnoreProperties(ignoreUnknown = false)`, so any extra field — `roles`, `admin`,
`authorities`, `permissions`, `verified`, `approved`, `password`, `passwordHash` — is
rejected with 400 before it ever reaches business logic (verified in
`ProfessionalActivationControllerTest`).

- An account that already carries `ADMIN` is refused with `409 ADMIN_ACCOUNT_PROTECTED`
  for either direction — see the P10-A.2.2 section above.
- `activated=true`: sets `verified=true`, `approved=true`, roles hardcoded to exactly
  `DOCTOR,REVIEWER` (never influenced by the request body), invalidates any pending
  verification challenges for that email, persists verified+approved+roles in one
  `UPDATE`, updates the in-memory cache only after that persist call returns
  successfully, audits `PROFESSIONAL_ACTIVATED`, and never issues a token.
- `activated=false`: sets `approved=false` and roles to exactly `PENDING_APPROVAL`,
  revokes every refresh token for that email (Postgres + in-memory), invalidates pending
  challenges, audits `PROFESSIONAL_DEACTIVATED`, never issues a token, never deletes the
  account or touches the password hash.
- The demo account is refused with 403 for either direction.

## P10-A.2.2: `verified`/`approved` are authoritative for authorization, not just `roles`

Before P10-A.2.2, `AuthAccountStateService` queried `verified`/`approved` from Postgres on
every request but discarded them — only `roles` fed into the request's effective claims.
That meant a row left inconsistent (e.g. `approved=false` but `roles` still
`DOCTOR,REVIEWER` from before a partial/legacy update) could still pass `AuthFilter`,
since `AuthFilter.isRestrictedAccount` only ever inspected `roles`.

`AuthAccountStateService.resolve` now computes **effective roles** from the persisted
row's `verified`/`approved` flags, not from whatever `roles` happens to still contain:

```java
boolean active = account.verified() && account.approved();
List<String> effectiveRoles = active ? account.roles() : List.of("PENDING_APPROVAL");
```

Those effective roles — never the row's raw `roles` — are what land in
`AuthFilter.AUTH_CLAIMS_ATTRIBUTE` and therefore in everything downstream
(`RoleAuthorizationService`, controllers). A `verified=false` or `approved=false` row is
treated as `PENDING_APPROVAL` for the whole request regardless of stale `DOCTOR`,
`REVIEWER`, or even `ADMIN` roles still sitting in the row — which in turn means only
`/api/auth/me` and `/api/auth/settings` remain reachable (existing `AuthFilter`
`PENDING_ALLOWED_PATHS` behavior), everything else gets `403 ACCESS_DENIED`. The row
itself is never modified by `AuthFilter` — this is a read-time projection, not a write.

`AuthAccountStateService.Resolution` now also carries the raw `verified`/`approved`
flags alongside the effective claims, for any caller that needs the account's real
authoritative state rather than just its effective roles.

## Superseded by P10-A.2.1: access tokens now revalidated on every request

**This section originally documented an accepted gap ("already-issued access tokens
survive deactivation") — that gap is closed as of P10-A.2.1.** See
`docs/P10_A1_DEMO_AND_PRODUCTION_HARDENING.md` and the P10-A.2.1 addendum in
`docs/P10_A_SECURITY_EVIDENCE.md` for the full picture: `AuthFilter` now revalidates the
caller's persisted account state (via `AuthAccountStateService` →
`PostgresAuthStoreService.findByEmailForAuthorization`) on every protected request in
production, and builds the request's effective `TokenService.Claims` from that
persisted state rather than trusting the JWT's own `roles` claim. A deactivated
account's still-unexpired access token is rejected (403) on the very next request —
proven end-to-end against real Postgres in
`AccountStateImmediateInvalidationIntegrationTest.accessTokenStopsWorkingImmediatelyAfterDeactivation`.
The cost is one additional Postgres read per protected request in production; outside
production the JWT's own claims are still trusted directly (no behavior change for
dev/test). A short-lived, invalidation-aware cache in front of that read is noted as a
possible future optimization, but was not needed to close the gap.

## First Railway deployment

```
SPRING_PROFILES_ACTIVE=production
PFI_AUTH_ENABLED=true
PFI_AUTH_DEMO_ENABLED=false
PFI_AUTH_EXPOSE_DEV_CODES=false
PFI_AUTH_JWT_SECRET=<new secure secret, 32+ bytes>
PFI_CORS_ALLOWED_ORIGINS=https://pfi-mvp-test-enzo-frontend.vercel.app
PFI_CORS_ALLOWED_ORIGIN_PATTERNS=
PFI_CORS_ALLOW_PREVIEW_PATTERNS=false
PFI_AI_SERVICE_MULTIPLANAR_CONTRACT_VERSION=v2

PFI_AUTH_BOOTSTRAP_ADMIN_ENABLED=true
PFI_AUTH_BOOTSTRAP_ADMIN_EMAIL=<real email>
PFI_AUTH_BOOTSTRAP_ADMIN_PASSWORD=<strong password, 16+ chars>
PFI_AUTH_BOOTSTRAP_ADMIN_FULL_NAME=<real name>
PFI_AUTH_BOOTSTRAP_ADMIN_LICENSE_NUMBER=<license>
PFI_AUTH_BOOTSTRAP_ADMIN_INSTITUTION=<institution>
```

## Second deployment (after confirming login works)

Set:

```
PFI_AUTH_BOOTSTRAP_ADMIN_ENABLED=false
```

And **remove entirely** (not just blank):

```
PFI_AUTH_BOOTSTRAP_ADMIN_PASSWORD
PFI_AUTH_BOOTSTRAP_ADMIN_EMAIL
PFI_AUTH_BOOTSTRAP_ADMIN_FULL_NAME
PFI_AUTH_BOOTSTRAP_ADMIN_LICENSE_NUMBER
PFI_AUTH_BOOTSTRAP_ADMIN_INSTITUTION
```

The backend continues starting normally because the ADMIN is already persisted in
Postgres — step 1 of the bootstrap policy (`hasNonDemoAdmin` true) short-circuits before
any of the removed variables would even be read.

## Rollback

- Revert the P10-A.2 commit — no schema migration is destructive: the bootstrap/
  activation methods only ever `INSERT ... ON CONFLICT DO NOTHING` or `UPDATE` existing
  rows; nothing is deleted.
- The persisted ADMIN account, `doctor_accounts` table, studies, and reviews are all
  left exactly as they are — do not delete the bootstrap ADMIN as part of a rollback.
- Do not restore the demo account or the previous (pre-P10-A.1) JWT secret as part of a
  rollback.
- If the deployment fails **before** the ADMIN was created (a validation error or a
  Postgres failure), fix the offending `PFI_AUTH_BOOTSTRAP_ADMIN_*` variable or Postgres
  connectivity and redeploy — the sanitized startup exception message says which
  category of check failed (never which email/password).
- If the ADMIN **was** successfully created, its password must never be written into any
  rollback runbook, ticket, or log — only the fact that it exists.
- There is intentionally no automated "delete the bootstrap admin" operation.

## Pending risks

- ~~Already-issued access tokens are not invalidated immediately on deactivation~~ —
  closed in P10-A.2.1 (`AuthAccountStateService` revalidates persisted state on every
  production request). See the note above.
- ~~`activated=true` on an existing ADMIN account silently strips its ADMIN role~~ —
  closed in P10-A.2.2 (`AdminAccountProtectedException` guard in
  `setProfessionalActivation`, before either branch runs).
- ~~`approved`/`verified` were queried but not used for authorization~~ — closed in
  P10-A.2.2 (`AuthAccountStateService` now derives effective roles from them).
- ~~`findNonDemoAdmin` accepted any row matching `roles LIKE '%ADMIN%'`, including
  `SUPERADMIN`/unverified/unapproved rows~~ — closed in P10-A.2.2 (exact-role,
  verified+approved re-check in Java on every candidate row).
- `AuthService.countOtherNonDemoAdmins` (last-ADMIN protection): when Postgres is
  enabled, it now uses the strict, exact-role
  `PostgresAuthStoreService.countActiveNonDemoAdminsExcluding` and fails closed (blocks
  the operation) if that query fails — it no longer relies on `listAccounts()` for this
  decision. Only outside Postgres (in-memory/dev/test mode) does it fall back to the
  in-memory cache, which is fine because Postgres is required in production regardless.
  As of P10-A.2.2 this check is unreachable from the professional-activation flow (the
  ADMIN guard above always fires first) but is kept as defense-in-depth for any other
  future admin-deactivating flow.
- Institutional activation's Postgres calls (`updateProfessionalActivation`,
  `deactivateProfessionalAndRevokeSessions`) are fail-closed by design (throw rather
  than silently succeed) — this means the activation endpoint is unusable in a pure
  in-memory (non-Postgres) deployment. That is considered acceptable because
  institutional activation is a production-oriented flow.
- No multi-tenancy / per-organization admin scoping exists — one ADMIN role governs the
  whole deployment, as before.
- Every protected request in production now costs one additional Postgres read
  (`findByEmailForAuthorization`). No caching layer was added — correctness was
  prioritized over the extra round-trip; a short-lived, invalidation-aware cache is
  listed as future work if this becomes a measured bottleneck.

## Future work (not implemented here)

- Google Secret Manager (or an equivalent) for `PFI_AUTH_BOOTSTRAP_ADMIN_PASSWORD` and
  `PFI_AUTH_JWT_SECRET` instead of plain Railway environment variables.
- A real transactional email provider so self-service email verification and
  password-reset flows become possible again, reducing reliance on institutional
  manual activation as the only production onboarding path.
- Optional: a short, invalidation-aware cache in front of the per-request account-state
  read described above, to trade a small, bounded staleness window for fewer Postgres
  round-trips — only worth doing if the extra read is ever measured to matter.
- The legacy `PATCH /api/auth/admin/professionals/approval` endpoint is still present
  (frontend compatibility) but fully delegates to the same domain operation as
  `/activation`; it should be removed once the frontend migrates, in P10-C.
