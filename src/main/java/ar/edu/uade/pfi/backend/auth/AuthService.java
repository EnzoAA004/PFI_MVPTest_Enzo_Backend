package ar.edu.uade.pfi.backend.auth;

import ar.edu.uade.pfi.backend.auth.dto.AuthDtos.PendingAuthResponse;
import ar.edu.uade.pfi.backend.auth.dto.AuthDtos.ProfessionalActivationResponse;
import ar.edu.uade.pfi.backend.auth.dto.AuthDtos.RegisterRequest;
import ar.edu.uade.pfi.backend.auth.dto.AuthDtos.SettingsRequest;
import ar.edu.uade.pfi.backend.auth.dto.AuthDtos.TokenResponse;
import ar.edu.uade.pfi.backend.auth.dto.AuthDtos.UserResponse;
import ar.edu.uade.pfi.backend.auth.exception.AdminAccountProtectedException;
import ar.edu.uade.pfi.backend.auth.exception.LastAdminProtectionException;
import ar.edu.uade.pfi.backend.service.AuditAction;
import ar.edu.uade.pfi.backend.service.AuditService;
import ar.edu.uade.pfi.backend.web.filter.TraceIdFilter;
import jakarta.annotation.PostConstruct;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.env.Environment;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

@Service
public class AuthService {
  /**
   * The single well-known demo account email. Blocking must key off this identity, not off roles,
   * because a previously-seeded demo account may already be persisted with ADMIN in Postgres even
   * after demo mode is turned off.
   */
  public static final String DEMO_ACCOUNT_EMAIL = "doctor.demo@pfi.local";

  private static final Duration CHALLENGE_TTL = Duration.ofMinutes(10);
  private final Map<String, DoctorAccount> accountsByEmail = new ConcurrentHashMap<>();
  private final Map<String, Challenge> challenges = new ConcurrentHashMap<>();
  private final Map<String, String> refreshTokens = new ConcurrentHashMap<>();
  private final PasswordHasher passwordHasher;
  private final TokenService tokenService;
  private final PostgresAuthStoreService postgresAuthStore;
  private final boolean exposeCodes;
  private final long refreshTokenSeconds;
  private final SecureRandom random = new SecureRandom();
  private final Environment environment;
  private final boolean demoEnabled;
  private final AuditService auditService;

  public AuthService(
      PasswordHasher passwordHasher,
      TokenService tokenService,
      PostgresAuthStoreService postgresAuthStore,
      @Value("${pfi.auth.expose-dev-codes:false}") boolean exposeCodes,
      @Value("${pfi.auth.refresh-token-seconds:604800}") long refreshTokenSeconds) {
    this(
        passwordHasher,
        tokenService,
        postgresAuthStore,
        exposeCodes,
        refreshTokenSeconds,
        null,
        false,
        null);
  }

  @Autowired
  public AuthService(
      PasswordHasher passwordHasher,
      TokenService tokenService,
      PostgresAuthStoreService postgresAuthStore,
      @Value("${pfi.auth.expose-dev-codes:false}") boolean exposeCodes,
      @Value("${pfi.auth.refresh-token-seconds:604800}") long refreshTokenSeconds,
      Environment environment,
      @Value("${pfi.auth.demo-enabled:false}") boolean demoEnabled,
      AuditService auditService) {
    this.passwordHasher = passwordHasher;
    this.tokenService = tokenService;
    this.postgresAuthStore = postgresAuthStore;
    this.exposeCodes = exposeCodes;
    this.refreshTokenSeconds = refreshTokenSeconds;
    this.environment = environment;
    this.demoEnabled = demoEnabled;
    this.auditService = auditService;
  }

  /**
   * If demo mode is not effectively enabled at startup, proactively revoke any lingering refresh
   * tokens for the demo account from a previous deployment/rollout — otherwise a still-valid
   * refresh token issued before this change could keep renewing access tokens indefinitely even
   * though the seed endpoint is now closed.
   */
  @PostConstruct
  void revokeDemoSessionsIfDemoDisabled() {
    if (!isDemoEffectivelyEnabled()) {
      postgresAuthStore.revokeRefreshTokensForEmail(DEMO_ACCOUNT_EMAIL);
      refreshTokens.values().removeIf(DEMO_ACCOUNT_EMAIL::equals);
    }
  }

  public PendingAuthResponse register(RegisterRequest request) {
    String email = normalizeEmail(request.email());
    if (findAccount(email).isPresent()) {
      throw new ResponseStatusException(
          HttpStatus.CONFLICT, "Ya existe una cuenta registrada con ese email");
    }
    DoctorAccount account =
        new DoctorAccount(
            UUID.randomUUID().toString(),
            request.fullName().trim(),
            email,
            passwordHasher.hash(request.password()),
            defaultString(request.licenseNumber()),
            defaultString(request.specialty()),
            defaultString(request.institution()),
            List.of("PENDING_APPROVAL"),
            Instant.now(),
            false,
            false,
            false,
            false);
    accountsByEmail.put(email, account);
    postgresAuthStore.saveAccount(account);
    return createChallenge(
        "REGISTER",
        email,
        "Código enviado para verificar el email profesional. Luego la cuenta queda pendiente de aprobación institucional.");
  }

  public Object login(String emailValue, String password) {
    String email = normalizeEmail(emailValue);
    if (isDemoAccountBlocked(email)) {
      throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Credenciales inválidas");
    }
    DoctorAccount account = findAccount(email).orElse(null);
    if (account == null || !passwordHasher.verify(password, account.passwordHash())) {
      throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Credenciales inválidas");
    }
    if (!account.verified()) {
      return createChallenge(
          "REGISTER", email, "La cuenta todavía requiere verificación del email profesional.");
    }
    if (!account.approved()) {
      return issueTokens(account);
    }
    if (account.twoFactorEnabled()) {
      return createChallenge(
          "LOGIN", email, "Código de doble verificación enviado para iniciar sesión.");
    }
    return issueTokens(account);
  }

  public TokenResponse verify(String challengeId, String code) {
    Challenge challenge = challenges.get(challengeId);
    if (challenge == null || challenge.expiresAt().isBefore(Instant.now())) {
      throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Código expirado o inválido");
    }
    if (!challenge.code().equals(code.trim())) {
      throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Código inválido");
    }
    if (isDemoAccountBlocked(challenge.email())) {
      challenges.remove(challengeId);
      throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Código expirado o inválido");
    }
    DoctorAccount account = findAccount(challenge.email()).orElse(null);
    if (account == null) {
      throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Cuenta no encontrada");
    }
    if ("REGISTER".equals(challenge.type())) {
      account.verify();
      accountsByEmail.put(account.email(), account);
      postgresAuthStore.markVerified(account.email());
    }
    challenges.remove(challengeId);
    return issueTokens(account);
  }

  public TokenResponse refresh(String refreshToken) {
    String email =
        postgresAuthStore
            .findEmailByRefreshToken(refreshToken)
            .orElseGet(() -> refreshTokens.get(refreshToken));
    if (email == null) {
      throw new ResponseStatusException(
          HttpStatus.UNAUTHORIZED, "Refresh token inválido o revocado");
    }
    if (isDemoAccountBlocked(email)) {
      revokeRefreshToken(refreshToken);
      throw new ResponseStatusException(
          HttpStatus.UNAUTHORIZED, "Refresh token inválido o revocado");
    }
    DoctorAccount account = findAccount(email).orElse(null);
    if (account == null || !account.verified() || !account.approved()) {
      throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Cuenta no disponible");
    }
    revokeRefreshToken(refreshToken);
    return issueTokens(account);
  }

  public void logout(String refreshToken) {
    revokeRefreshToken(refreshToken);
  }

  /**
   * P10-B.2: reports {@code claims.roles()} — the caller's *effective* roles for this specific
   * request, as already resolved by AuthFilter/AuthAccountStateService — not {@code
   * account.roles()} directly. Roles are no longer wiped on deactivation (they are preserved in
   * storage for reactivation), so reading the raw persisted value here would incorrectly show a
   * deactivated account's old professional roles on its own `/me` instead of the PENDING_APPROVAL
   * state it is actually restricted to.
   */
  public UserResponse currentUser(TokenService.Claims claims) {
    DoctorAccount account = findAccount(normalizeEmail(claims.email())).orElse(null);
    if (account == null) {
      return new UserResponse(
          claims.subject(),
          claims.name(),
          claims.email(),
          "",
          "",
          "",
          claims.roles(),
          true,
          true,
          false,
          true);
    }
    return toUser(account, claims.roles());
  }

  /**
   * Same effective-roles rationale as {@link #currentUser} — /settings is reachable while
   * PENDING_APPROVAL too.
   */
  public UserResponse updateSettings(TokenService.Claims claims, SettingsRequest request) {
    DoctorAccount account =
        findAccount(normalizeEmail(claims.email()))
            .orElseThrow(
                () -> new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Cuenta no encontrada"));
    if (request.twoFactorEnabled() != null) account.setTwoFactorEnabled(request.twoFactorEnabled());
    if (request.onboardingCompleted() != null)
      account.setOnboardingCompleted(request.onboardingCompleted());
    accountsByEmail.put(account.email(), account);
    postgresAuthStore.updateSettings(
        account.email(), request.twoFactorEnabled(), request.onboardingCompleted());
    return toUser(account, claims.roles());
  }

  public List<UserResponse> listProfessionals(TokenService.Claims claims) {
    requireAdmin(claims);
    List<DoctorAccount> persisted = postgresAuthStore.listAccounts();
    if (!persisted.isEmpty()) {
      persisted.forEach(account -> accountsByEmail.put(account.email(), account));
      return persisted.stream().map(this::toUser).toList();
    }
    return accountsByEmail.values().stream().map(this::toUser).toList();
  }

  /**
   * Legacy endpoint kept only for frontend compatibility (see AuthController) — it now delegates to
   * the exact same domain operation as institutional activation, so it can no longer bypass
   * last-ADMIN protection, demo blocking, fail-closed persistence, session revocation, or challenge
   * invalidation the way it used to. Deprecated: pending removal once the frontend migrates to
   * /activation (P10-C).
   */
  @Deprecated
  public UserResponse approveProfessional(
      TokenService.Claims claims, String emailValue, boolean approved) {
    setProfessionalActivation(claims, emailValue, approved);
    DoctorAccount account =
        findAccount(normalizeEmail(emailValue))
            .orElseThrow(
                () ->
                    new ResponseStatusException(HttpStatus.NOT_FOUND, "Profesional no encontrado"));
    return toUser(account);
  }

  /**
   * "Institutional manual activation" — an ADMIN vouches for a professional account outside of any
   * email-verification flow. This is deliberately NOT the same thing as email verification: it does
   * not claim an email was sent or confirmed, and it never issues a token. Activating never grants
   * ADMIN (the request DTO itself only carries email+activated, so there is nothing to smuggle in
   * from the caller) and, as of P10-B.2, never replaces an existing professional role set either —
   * see {@link #resolveActivationRoles}.
   */
  public ProfessionalActivationResponse activateProfessional(
      TokenService.Claims claims, String emailValue, boolean activated) {
    return setProfessionalActivation(claims, emailValue, activated);
  }

  /**
   * The single domain operation both /approval (legacy) and /activation delegate to — see
   * class-level P10-A.2.1 notes. There is exactly one implementation of "change a professional's
   * active status" in this service.
   *
   * <p>This is a PROFESSIONAL flow only: an account that already carries ADMIN can never be touched
   * here, in either direction (activated=true or activated=false) — its roles, approved/verified
   * flags, password, and everything else stay untouched. Administrator management belongs to a
   * separate, dedicated flow.
   */
  private ProfessionalActivationResponse setProfessionalActivation(
      TokenService.Claims claims, String emailValue, boolean activated) {
    requireAdmin(claims);
    String email = normalizeEmail(emailValue);
    if (DEMO_ACCOUNT_EMAIL.equals(email)) {
      throw new ResponseStatusException(
          HttpStatus.FORBIDDEN, "No tiene permisos para realizar esta operacion.");
    }
    DoctorAccount account =
        findAccount(email)
            .orElseThrow(
                () ->
                    new ResponseStatusException(HttpStatus.NOT_FOUND, "Profesional no encontrado"));
    if (account.roles() != null && account.roles().contains("ADMIN")) {
      throw new AdminAccountProtectedException();
    }
    return activated ? activate(claims, account) : deactivate(claims, account);
  }

  private ProfessionalActivationResponse activate(
      TokenService.Claims claims, DoctorAccount account) {
    List<String> targetRoles = resolveActivationRoles(account.roles());
    postgresAuthStore.updateProfessionalActivation(account.email(), true, true, targetRoles);
    account.verify();
    account.approve(true);
    account.setRoles(targetRoles);
    accountsByEmail.put(account.email(), account);
    invalidateChallengesFor(account.email());
    auditActivation(claims, account, AuditAction.PROFESSIONAL_ACTIVATED, true, targetRoles);
    return new ProfessionalActivationResponse(
        account.email(), "activated", true, true, targetRoles);
  }

  /**
   * P10-B.2: activation changes account *status* only — it must never replace an already-meaningful
   * role set with the default DOCTOR+REVIEWER pair. The default is only used the very first time an
   * account is approved (its roles are still exactly the {@code PENDING_APPROVAL} placeholder from
   * registration, or empty/corrupt) — any real role set already on the account (a single role, a
   * restricted subset, an extra role) survives activation/reactivation unchanged, byte for byte.
   */
  private List<String> resolveActivationRoles(List<String> currentRoles) {
    List<String> real =
        currentRoles == null
            ? List.of()
            : currentRoles.stream().filter(role -> !"PENDING_APPROVAL".equals(role)).toList();
    return real.isEmpty() ? List.of("DOCTOR", "REVIEWER") : real;
  }

  /**
   * Deactivation + session revocation happen inside a single Postgres transaction
   * (PostgresAuthStoreService.deactivateProfessionalAndRevokeSessions) — the in-memory
   * cache/refresh-token map are only mutated after that call returns successfully, so a failure
   * never leaves the account looking deactivated locally while Postgres still has it active (or
   * vice versa).
   *
   * <p>P10-B.2: deactivation changes account *status* only — roles are read back and persisted
   * completely unchanged (never replaced with PENDING_APPROVAL). Access is still correctly
   * restricted while deactivated: AuthAccountStateService derives the request's *effective* roles
   * from verified/approved, not from this roles column (see its class docs), and
   * AuthService.issueTokens does the same when minting any new token for this account while it
   * remains unapproved.
   */
  private ProfessionalActivationResponse deactivate(
      TokenService.Claims claims, DoctorAccount account) {
    if (isLastNonDemoAdmin(account)) {
      throw new LastAdminProtectionException();
    }
    List<String> preservedRoles =
        account.roles() == null ? List.of() : List.copyOf(account.roles());
    postgresAuthStore.deactivateProfessionalAndRevokeSessions(
        account.email(), account.verified(), preservedRoles);
    account.approve(false);
    accountsByEmail.put(account.email(), account);
    refreshTokens.values().removeIf(account.email()::equals);
    invalidateChallengesFor(account.email());
    auditActivation(claims, account, AuditAction.PROFESSIONAL_DEACTIVATED, false, preservedRoles);
    return new ProfessionalActivationResponse(
        account.email(), "deactivated", account.verified(), false, preservedRoles);
  }

  /**
   * Best-effort, never throws — used only for the ADMIN diagnostics summary flag, not for gating.
   */
  public boolean hasConfiguredAdminSafe() {
    try {
      return postgresAuthStore.enabled() && postgresAuthStore.hasNonDemoAdmin(DEMO_ACCOUNT_EMAIL);
    } catch (RuntimeException ex) {
      return false;
    }
  }

  private boolean isLastNonDemoAdmin(DoctorAccount account) {
    if (DEMO_ACCOUNT_EMAIL.equals(account.email()) || !account.roles().contains("ADMIN")) {
      return false;
    }
    return countOtherNonDemoAdmins(account.email()) == 0;
  }

  /**
   * When Postgres is enabled, this is a security decision and must be fail-closed: an inability to
   * count administrators blocks the operation (via LastAdminProtectionException) rather than
   * assuming other admins exist. Outside Postgres (in-memory/dev/test mode), falls back to the
   * in-memory cache — acceptable there because Postgres is required in production by
   * SecurityStartupValidator anyway.
   */
  private long countOtherNonDemoAdmins(String excludedEmail) {
    if (postgresAuthStore.enabled()) {
      try {
        return postgresAuthStore.countActiveNonDemoAdminsExcluding(excludedEmail);
      } catch (RuntimeException ex) {
        throw new LastAdminProtectionException();
      }
    }
    return accountsByEmail.values().stream()
        .filter(a -> !DEMO_ACCOUNT_EMAIL.equals(a.email()))
        .filter(a -> !a.email().equals(excludedEmail))
        .filter(a -> a.roles().contains("ADMIN"))
        .count();
  }

  private void invalidateChallengesFor(String email) {
    challenges.values().removeIf(challenge -> email.equals(challenge.email()));
  }

  /**
   * P10-B.1 §9: actorId/entityId are technical UUIDs, never an email — {@code claims.subject()} is
   * the acting ADMIN's account id (post-revalidation, since {@code claims} here always comes from
   * an already-authenticated/authorized caller), {@code account.id()} is the target account's id.
   * traceId comes from the current request's MDC (set by TraceIdFilter), not an empty string.
   * AuditService.record() is itself fail-safe (never throws), so no try/catch is needed here.
   */
  private void auditActivation(
      TokenService.Claims claims,
      DoctorAccount account,
      AuditAction action,
      boolean activated,
      List<String> roles) {
    if (auditService == null) return;
    String traceId = MDC.get(TraceIdFilter.TRACE_ID_MDC_KEY);
    auditService.record(
        claims.subject(),
        action.name(),
        account.id(),
        traceId,
        Map.of(
            "entityType",
            "professional_account",
            "outcome",
            "success",
            "activated",
            activated,
            "roles",
            roles));
  }

  /**
   * Issues a fully-approved ADMIN/DOCTOR/REVIEWER token with no credential check at all. Refused
   * (404, to avoid advertising the endpoint's existence) whenever demo mode is not effectively
   * enabled — i.e. always in production, and locally unless PFI_AUTH_DEMO_ENABLED=true was
   * explicitly set. This check is enforced here at the service layer regardless of how the request
   * reached this method (AuthFilter only controls whether a token is *required* to reach it).
   */
  public TokenResponse seedDemoDoctor() {
    if (!isDemoEffectivelyEnabled()) {
      throw new ResponseStatusException(HttpStatus.NOT_FOUND, "No encontrado");
    }
    String email = DEMO_ACCOUNT_EMAIL;
    DoctorAccount existing = findAccount(email).orElse(null);
    if (existing != null) {
      existing.verify();
      existing.approve(true);
      existing.setRoles(List.of("ADMIN", "DOCTOR", "REVIEWER"));
      existing.setTwoFactorEnabled(false);
      accountsByEmail.put(email, existing);
      postgresAuthStore.saveAccount(existing);
      return issueTokens(existing);
    }
    DoctorAccount account =
        new DoctorAccount(
            UUID.randomUUID().toString(),
            "Dra. Demo Reviewer",
            email,
            passwordHasher.hash("Demo1234!"),
            "MN-DEMO-2026",
            "Radiología / Columna lumbar",
            "PFI Academic Lab",
            List.of("ADMIN", "DOCTOR", "REVIEWER"),
            Instant.now(),
            true,
            true,
            false,
            true);
    accountsByEmail.put(email, account);
    postgresAuthStore.saveAccount(account);
    return issueTokens(account);
  }

  public Map<String, Object> diagnostics() {
    return Map.of(
        "enabled", true,
        "mode", postgresAuthStore.enabled() ? "jwt-postgres" : "jwt-memory",
        "professionalAccountsPersisted", postgresAuthStore.enabled(),
        "professionalApprovalRequired", true,
        "twoFactorConfigurable", true,
        "refreshTokensRevocable", true,
        "status", "enabled");
  }

  private Optional<DoctorAccount> findAccount(String email) {
    DoctorAccount cached = accountsByEmail.get(email);
    if (cached != null) return Optional.of(cached);
    Optional<DoctorAccount> persisted = postgresAuthStore.findByEmail(email);
    persisted.ifPresent(account -> accountsByEmail.put(email, account));
    return persisted;
  }

  private boolean isProductionProfile() {
    if (environment == null) return false;
    for (String profile : environment.getActiveProfiles()) {
      String normalized = profile.toLowerCase(Locale.ROOT);
      if (normalized.equals("production") || normalized.equals("prod")) return true;
    }
    return false;
  }

  private boolean isDemoEffectivelyEnabled() {
    return demoEnabled && !isProductionProfile();
  }

  /**
   * Blocks the known demo account identity whenever demo mode is not effectively enabled —
   * regardless of whatever roles it may already carry in a persisted store.
   */
  private boolean isDemoAccountBlocked(String emailValue) {
    return DEMO_ACCOUNT_EMAIL.equals(normalizeEmail(emailValue)) && !isDemoEffectivelyEnabled();
  }

  private void requireAdmin(TokenService.Claims claims) {
    if (claims == null || claims.roles() == null || !claims.roles().contains("ADMIN")) {
      throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Requiere rol ADMIN");
    }
  }

  private PendingAuthResponse createChallenge(String type, String email, String message) {
    String challengeId = UUID.randomUUID().toString();
    String code = String.format("%06d", random.nextInt(1_000_000));
    challenges.put(
        challengeId,
        new Challenge(challengeId, email, type, code, Instant.now().plus(CHALLENGE_TTL)));
    return new PendingAuthResponse(
        challengeId,
        "email-demo",
        (int) CHALLENGE_TTL.toSeconds(),
        message,
        exposeCodes ? code : null);
  }

  /**
   * P10-B.2: always mints the access token — and the UserResponse embedded alongside it — from
   * *effective* roles (verified && approved ? account.roles() : PENDING_APPROVAL), never the
   * account's raw stored roles directly. This matters because roles are no longer wiped on
   * deactivation (they're preserved for reactivation) — without this, a login or refresh for a
   * still-unapproved/ deactivated account would mint a token (and show a response body) carrying
   * its real professional roles. The account's raw roles in storage are otherwise untouched; this
   * only affects what this one response embeds.
   */
  private TokenResponse issueTokens(DoctorAccount account) {
    List<String> effectiveRoles = effectiveRoles(account);
    String accessToken = tokenService.issueAccessToken(account, effectiveRoles);
    String refreshToken =
        Base64.getUrlEncoder()
            .withoutPadding()
            .encodeToString(UUID.randomUUID().toString().getBytes());
    Instant expiresAt = Instant.now().plusSeconds(refreshTokenSeconds);
    refreshTokens.put(refreshToken, account.email());
    postgresAuthStore.saveRefreshToken(refreshToken, account.email(), expiresAt);
    return new TokenResponse(
        accessToken,
        refreshToken,
        "Bearer",
        tokenService.accessTokenSeconds(),
        toUser(account, effectiveRoles));
  }

  private void revokeRefreshToken(String refreshToken) {
    if (refreshToken == null || refreshToken.isBlank()) return;
    refreshTokens.remove(refreshToken);
    postgresAuthStore.revokeRefreshToken(refreshToken);
  }

  /**
   * ADMIN-facing / self-activation-result views: shows the account's raw persisted roles (useful —
   * e.g. an ADMIN can see a deactivated account's preserved roles).
   */
  private UserResponse toUser(DoctorAccount account) {
    return toUser(account, account.roles());
  }

  /**
   * {@code /me} and {@code /settings} use this with the caller's *effective* roles instead — see
   * call sites.
   */
  private UserResponse toUser(DoctorAccount account, List<String> roles) {
    return new UserResponse(
        account.id(),
        account.fullName(),
        account.email(),
        account.licenseNumber(),
        account.specialty(),
        account.institution(),
        roles,
        account.verified(),
        account.approved(),
        account.twoFactorEnabled(),
        account.onboardingCompleted());
  }

  /**
   * verified && approved ? account.roles() : PENDING_APPROVAL — see issueTokens/currentUser for why
   * this must never be the raw roles column directly.
   */
  private List<String> effectiveRoles(DoctorAccount account) {
    return account.verified() && account.approved() ? account.roles() : List.of("PENDING_APPROVAL");
  }

  private String normalizeEmail(String value) {
    return value == null ? "" : value.trim().toLowerCase();
  }

  private String defaultString(String value) {
    return value == null ? "" : value.trim();
  }

  private record Challenge(String id, String email, String type, String code, Instant expiresAt) {}
}
