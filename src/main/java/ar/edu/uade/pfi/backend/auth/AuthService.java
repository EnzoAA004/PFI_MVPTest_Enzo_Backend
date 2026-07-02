package ar.edu.uade.pfi.backend.auth;

import ar.edu.uade.pfi.backend.auth.dto.AuthDtos.PendingAuthResponse;
import ar.edu.uade.pfi.backend.auth.dto.AuthDtos.RegisterRequest;
import ar.edu.uade.pfi.backend.auth.dto.AuthDtos.TokenResponse;
import ar.edu.uade.pfi.backend.auth.dto.AuthDtos.UserResponse;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

@Service
public class AuthService {
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

    public AuthService(
        PasswordHasher passwordHasher,
        TokenService tokenService,
        PostgresAuthStoreService postgresAuthStore,
        @Value("${pfi.auth.expose-dev-codes:true}") boolean exposeCodes,
        @Value("${pfi.auth.refresh-token-seconds:604800}") long refreshTokenSeconds
    ) {
        this.passwordHasher = passwordHasher;
        this.tokenService = tokenService;
        this.postgresAuthStore = postgresAuthStore;
        this.exposeCodes = exposeCodes;
        this.refreshTokenSeconds = refreshTokenSeconds;
    }

    public PendingAuthResponse register(RegisterRequest request) {
        String email = normalizeEmail(request.email());
        if (findAccount(email).isPresent()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Ya existe una cuenta registrada con ese email");
        }
        DoctorAccount account = new DoctorAccount(
            UUID.randomUUID().toString(),
            request.fullName().trim(),
            email,
            passwordHasher.hash(request.password()),
            defaultString(request.licenseNumber()),
            defaultString(request.specialty()),
            defaultString(request.institution()),
            List.of("DOCTOR", "REVIEWER"),
            Instant.now(),
            false
        );
        accountsByEmail.put(email, account);
        postgresAuthStore.saveAccount(account);
        return createChallenge("REGISTER", email, "Código de verificación enviado para completar el registro profesional.");
    }

    public PendingAuthResponse login(String emailValue, String password) {
        String email = normalizeEmail(emailValue);
        DoctorAccount account = findAccount(email).orElse(null);
        if (account == null || !passwordHasher.verify(password, account.passwordHash())) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Credenciales inválidas");
        }
        if (!account.verified()) {
            return createChallenge("REGISTER", email, "La cuenta todavía requiere verificación de registro profesional.");
        }
        return createChallenge("LOGIN", email, "Código de doble verificación enviado para iniciar sesión.");
    }

    public TokenResponse verify(String challengeId, String code) {
        Challenge challenge = challenges.get(challengeId);
        if (challenge == null || challenge.expiresAt().isBefore(Instant.now())) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Código expirado o inválido");
        }
        if (!challenge.code().equals(code.trim())) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Código inválido");
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
        String email = postgresAuthStore.findEmailByRefreshToken(refreshToken)
            .orElseGet(() -> refreshTokens.get(refreshToken));
        if (email == null) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Refresh token inválido o revocado");
        }
        DoctorAccount account = findAccount(email).orElse(null);
        if (account == null || !account.verified()) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Cuenta no disponible");
        }
        revokeRefreshToken(refreshToken);
        return issueTokens(account);
    }

    public void logout(String refreshToken) {
        revokeRefreshToken(refreshToken);
    }

    public UserResponse currentUser(TokenService.Claims claims) {
        DoctorAccount account = findAccount(normalizeEmail(claims.email())).orElse(null);
        if (account == null) {
            return new UserResponse(claims.subject(), claims.name(), claims.email(), "", "", "", claims.roles(), true);
        }
        return toUser(account);
    }

    public TokenResponse seedDemoDoctor() {
        String email = "doctor.demo@pfi.local";
        DoctorAccount existing = findAccount(email).orElse(null);
        if (existing != null) {
            if (!existing.verified()) {
                existing.verify();
                accountsByEmail.put(email, existing);
                postgresAuthStore.markVerified(email);
            }
            return issueTokens(existing);
        }
        DoctorAccount account = new DoctorAccount(
            UUID.randomUUID().toString(),
            "Dra. Demo Reviewer",
            email,
            passwordHasher.hash("Demo1234!"),
            "MN-DEMO-2026",
            "Radiología / Columna lumbar",
            "PFI Academic Lab",
            List.of("DOCTOR", "REVIEWER"),
            Instant.now(),
            true
        );
        accountsByEmail.put(email, account);
        postgresAuthStore.saveAccount(account);
        return issueTokens(account);
    }

    public Map<String, Object> diagnostics() {
        return Map.of(
            "enabled", true,
            "mode", postgresAuthStore.enabled() ? "jwt-postgres" : "jwt-memory",
            "professionalAccountsPersisted", postgresAuthStore.enabled(),
            "refreshTokensRevocable", true,
            "status", "enabled"
        );
    }

    private Optional<DoctorAccount> findAccount(String email) {
        DoctorAccount cached = accountsByEmail.get(email);
        if (cached != null) return Optional.of(cached);
        Optional<DoctorAccount> persisted = postgresAuthStore.findByEmail(email);
        persisted.ifPresent(account -> accountsByEmail.put(email, account));
        return persisted;
    }

    private PendingAuthResponse createChallenge(String type, String email, String message) {
        String challengeId = UUID.randomUUID().toString();
        String code = String.format("%06d", random.nextInt(1_000_000));
        challenges.put(challengeId, new Challenge(challengeId, email, type, code, Instant.now().plus(CHALLENGE_TTL)));
        return new PendingAuthResponse(
            challengeId,
            "email-demo",
            (int) CHALLENGE_TTL.toSeconds(),
            message,
            exposeCodes ? code : null
        );
    }

    private TokenResponse issueTokens(DoctorAccount account) {
        String accessToken = tokenService.issueAccessToken(account);
        String refreshToken = Base64.getUrlEncoder().withoutPadding().encodeToString(UUID.randomUUID().toString().getBytes());
        Instant expiresAt = Instant.now().plusSeconds(refreshTokenSeconds);
        refreshTokens.put(refreshToken, account.email());
        postgresAuthStore.saveRefreshToken(refreshToken, account.email(), expiresAt);
        return new TokenResponse(accessToken, refreshToken, "Bearer", tokenService.accessTokenSeconds(), toUser(account));
    }

    private void revokeRefreshToken(String refreshToken) {
        if (refreshToken == null || refreshToken.isBlank()) return;
        refreshTokens.remove(refreshToken);
        postgresAuthStore.revokeRefreshToken(refreshToken);
    }

    private UserResponse toUser(DoctorAccount account) {
        return new UserResponse(
            account.id(),
            account.fullName(),
            account.email(),
            account.licenseNumber(),
            account.specialty(),
            account.institution(),
            account.roles(),
            account.verified()
        );
    }

    private String normalizeEmail(String value) {
        return value == null ? "" : value.trim().toLowerCase();
    }

    private String defaultString(String value) {
        return value == null ? "" : value.trim();
    }

    private record Challenge(String id, String email, String type, String code, Instant expiresAt) {}
}
