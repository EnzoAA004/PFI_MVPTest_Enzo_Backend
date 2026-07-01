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
    private final boolean exposeCodes;
    private final SecureRandom random = new SecureRandom();

    public AuthService(
        PasswordHasher passwordHasher,
        TokenService tokenService,
        @Value("${pfi.auth.expose-dev-codes}") boolean exposeCodes
    ) {
        this.passwordHasher = passwordHasher;
        this.tokenService = tokenService;
        this.exposeCodes = exposeCodes;
    }

    public PendingAuthResponse register(RegisterRequest request) {
        String email = normalizeEmail(request.email());
        if (accountsByEmail.containsKey(email)) {
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
        return createChallenge("REGISTER", email, "Código de verificación enviado para completar el registro.");
    }

    public PendingAuthResponse login(String emailValue, String password) {
        String email = normalizeEmail(emailValue);
        DoctorAccount account = accountsByEmail.get(email);
        if (account == null || !passwordHasher.verify(password, account.passwordHash())) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Credenciales inválidas");
        }
        if (!account.verified()) {
            return createChallenge("REGISTER", email, "La cuenta todavía requiere verificación de registro.");
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
        DoctorAccount account = accountsByEmail.get(challenge.email());
        if (account == null) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Cuenta no encontrada");
        }
        if ("REGISTER".equals(challenge.type())) {
            account.verify();
        }
        challenges.remove(challengeId);
        return issueTokens(account);
    }

    public TokenResponse refresh(String refreshToken) {
        String email = refreshTokens.get(refreshToken);
        if (email == null) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Refresh token inválido");
        }
        DoctorAccount account = accountsByEmail.get(email);
        if (account == null || !account.verified()) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Cuenta no disponible");
        }
        return issueTokens(account);
    }

    public UserResponse currentUser(TokenService.Claims claims) {
        DoctorAccount account = accountsByEmail.get(normalizeEmail(claims.email()));
        if (account == null) {
            return new UserResponse(claims.subject(), claims.name(), claims.email(), "", "", "", claims.roles(), true);
        }
        return toUser(account);
    }

    public TokenResponse seedDemoDoctor() {
        String email = "doctor.demo@pfi.local";
        DoctorAccount existing = accountsByEmail.get(email);
        if (existing != null) {
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
        return issueTokens(account);
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
        refreshTokens.put(refreshToken, account.email());
        return new TokenResponse(accessToken, refreshToken, "Bearer", tokenService.accessTokenSeconds(), toUser(account));
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
