# P10-A.2 — Admin Bootstrap & Institutional Activation

Commit base: `bb301b48101b51789745e48a80abc0f87715e12e` (P10-A.1)
Repository: `EnzoAA004/PFI_MVPTest_Enzo_Backend`

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
   license are never touched.
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

## Last-ADMIN lockout protection

There is currently no dedicated "edit ADMIN roles" endpoint — the only place an ADMIN's
status can change today is professional deactivation
(`PATCH .../activation` with `activated=false`), and it now refuses to deactivate an
account if doing so would leave zero non-demo ADMIN accounts, responding `409
LAST_ADMIN_PROTECTION`. The demo account is explicitly excluded from this count (it
can never be the thing standing between "locked out" and "not locked out"). See
`AuthService.isLastNonDemoAdmin`/`countOtherNonDemoAdmins` and `LastAdminProtectionTest`.

## Institutional activation endpoint

`PATCH /api/auth/admin/professionals/activation` — ADMIN-only
(`RoleAuthorizationService.requireAdmin`, a mandatory constructor dependency on
`AuthController`, no null-check bypass). Request body accepts **only** `email` and
`activated`; the DTO (`ProfessionalActivationRequest`) is annotated
`@JsonIgnoreProperties(ignoreUnknown = false)`, so any extra field — `roles`, `admin`,
`authorities`, `permissions`, `verified`, `approved`, `password`, `passwordHash` — is
rejected with 400 before it ever reaches business logic (verified in
`ProfessionalActivationControllerTest`).

- `activated=true`: sets `verified=true`, `approved=true`, roles hardcoded to exactly
  `DOCTOR,REVIEWER` (never influenced by the request body), invalidates any pending
  verification challenges for that email, persists verified+approved+roles in one
  `UPDATE`, updates the in-memory cache only after that persist call returns
  successfully, audits `PROFESSIONAL_ACTIVATED`, and never issues a token.
- `activated=false`: applies the last-ADMIN check, sets `approved=false` and roles to
  exactly `PENDING_APPROVAL`, revokes every refresh token for that email (Postgres +
  in-memory), invalidates pending challenges, audits `PROFESSIONAL_DEACTIVATED`, never
  issues a token, never deletes the account or touches the password hash.
- The demo account is refused with 403 for either direction.

## Known limitation: already-issued access tokens survive deactivation

Deactivation revokes **refresh tokens** immediately, so a deactivated account cannot
renew its session. It does **not** invalidate an access token that was already issued
and has not yet expired — `AuthFilter` only ever validates the JWT signature/expiry, it
does not query Postgres on every request. A brand-new login attempt after
deactivation *is* safe: `AuthService.login` re-reads the persisted account, so the new
token it issues only ever carries `PENDING_APPROVAL` (verified in
`ProfessionalActivationIntegrationTest.deactivatedAccountLoginOnlyEverGetsPendingApprovalRole`).
The exposure window is therefore bounded by the access-token TTL (`pfi.auth.access-token-seconds`,
1 hour by default) rather than being open-ended. A full "re-check account state on every
authenticated request" implementation was deliberately not attempted in this pass — it
would add a Postgres round-trip to every protected request, a meaningful architecture
and performance change that deserves its own review rather than being bundled into an
already-large security patch. **This is an honest, documented gap, not a silent one.**

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

- Already-issued access tokens are not invalidated immediately on deactivation (see
  above) — bounded by the 1-hour default TTL.
- `AuthService.countOtherNonDemoAdmins` (last-ADMIN protection) reads from
  `postgresAuthStore.listAccounts()` when Postgres is enabled, else from the in-memory
  cache — in the extremely unlikely case that the in-memory cache is missing an admin
  account that exists only in Postgres and Postgres is disabled, the check could
  under-count. In production (Postgres always enabled per P10-A.1's own requirements)
  this does not apply.
- Institutional activation's Postgres calls (`updateProfessionalActivation`) are
  fail-closed by design (throw rather than silently succeed) — this means the
  activation endpoint is unusable in a pure in-memory (non-Postgres) deployment. That is
  considered acceptable because institutional activation is a production-oriented flow.
- No multi-tenancy / per-organization admin scoping exists — one ADMIN role governs the
  whole deployment, as before.

## Future work (not implemented here)

- Google Secret Manager (or an equivalent) for `PFI_AUTH_BOOTSTRAP_ADMIN_PASSWORD` and
  `PFI_AUTH_JWT_SECRET` instead of plain Railway environment variables.
- A real transactional email provider so self-service email verification and
  password-reset flows become possible again, reducing reliance on institutional
  manual activation as the only production onboarding path.
- Optional: a live-session invalidation mechanism (e.g. a short "deny list" cache or
  reducing the default access-token TTL further) to close the already-issued-token gap
  described above without a full per-request DB check.
