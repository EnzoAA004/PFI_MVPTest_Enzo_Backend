package ar.edu.uade.pfi.backend.config;

import jakarta.annotation.PostConstruct;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

/**
 * Fail-closed startup gate for production profiles. This never runs (and never blocks
 * `mvn test`) unless SPRING_PROFILES_ACTIVE explicitly contains "production"/"prod" —
 * default/dev/test runs are unaffected. In production it refuses to let the context
 * finish starting if any of: the JWT signing key, auth being enabled, demo mode being
 * disabled, dev verification codes being hidden, or CORS being pinned to real HTTPS
 * origins (no wildcard) does not hold.
 */
@Component
public class SecurityStartupValidator {
    /** Must match TokenService's own default so we can detect "nobody configured a real secret". */
    static final String DEMO_DEFAULT_SECRET = "pfi-demo-change-me-2026";
    static final int MIN_SECRET_BYTES = 32;

    private final Environment environment;
    private final String jwtSecret;
    private final boolean authEnabled;
    private final boolean demoEnabled;
    private final boolean exposeDevCodes;
    private final String corsAllowedOrigins;
    private final String corsAllowedOriginPatterns;
    private final boolean allowPreviewPatterns;

    public SecurityStartupValidator(
        Environment environment,
        @Value("${pfi.auth.jwt-secret:pfi-demo-change-me-2026}") String jwtSecret,
        @Value("${pfi.auth.enabled:true}") boolean authEnabled,
        @Value("${pfi.auth.demo-enabled:false}") boolean demoEnabled,
        @Value("${pfi.auth.expose-dev-codes:false}") boolean exposeDevCodes,
        @Value("${pfi.cors.allowed-origins:}") String corsAllowedOrigins,
        @Value("${pfi.cors.allowed-origin-patterns:}") String corsAllowedOriginPatterns,
        @Value("${pfi.cors.allow-preview-patterns:false}") boolean allowPreviewPatterns
    ) {
        this.environment = environment;
        this.jwtSecret = jwtSecret;
        this.authEnabled = authEnabled;
        this.demoEnabled = demoEnabled;
        this.exposeDevCodes = exposeDevCodes;
        this.corsAllowedOrigins = corsAllowedOrigins;
        this.corsAllowedOriginPatterns = corsAllowedOriginPatterns;
        this.allowPreviewPatterns = allowPreviewPatterns;
    }

    @PostConstruct
    void validate() {
        if (!isProductionProfile()) {
            return;
        }
        validateJwtSecret();
        validateAuthEnabled();
        validateDemoDisabled();
        validateDevCodesHidden();
        validateCors();
    }

    private void validateJwtSecret() {
        if (jwtSecret == null || jwtSecret.isBlank()) {
            fail("pfi.auth.jwt-secret (PFI_AUTH_JWT_SECRET) is required in production and must not be blank");
        }
        if (DEMO_DEFAULT_SECRET.equals(jwtSecret)) {
            fail("pfi.auth.jwt-secret must not use the repository's demo default value in production");
        }
        int effectiveBytes = jwtSecret.getBytes(StandardCharsets.UTF_8).length;
        if (effectiveBytes < MIN_SECRET_BYTES) {
            fail("pfi.auth.jwt-secret is too short for HS256 in production; need at least "
                + MIN_SECRET_BYTES + " bytes, got " + effectiveBytes);
        }
    }

    private void validateAuthEnabled() {
        if (!authEnabled) {
            fail("pfi.auth.enabled (PFI_AUTH_ENABLED) must be true in production; "
                + "running without AuthFilter is not permitted outside tests/local");
        }
    }

    private void validateDemoDisabled() {
        if (demoEnabled) {
            fail("pfi.auth.demo-enabled (PFI_AUTH_DEMO_ENABLED) must be false in production");
        }
    }

    private void validateDevCodesHidden() {
        if (exposeDevCodes) {
            fail("pfi.auth.expose-dev-codes (PFI_AUTH_EXPOSE_DEV_CODES) must be false in production; "
                + "verification codes must never be returned to clients");
        }
    }

    private void validateCors() {
        List<String> origins = splitTrimmed(corsAllowedOrigins);
        if (origins.isEmpty()) {
            fail("pfi.cors.allowed-origins (PFI_CORS_ALLOWED_ORIGINS) must configure at least one exact HTTPS origin in production");
        }
        for (String origin : origins) {
            if ("*".equals(origin)) {
                fail("pfi.cors.allowed-origins must not contain a literal \"*\" in production");
            }
            if (!origin.toLowerCase(Locale.ROOT).startsWith("https://")) {
                fail("pfi.cors.allowed-origins must be exact HTTPS origins in production, found a non-HTTPS entry");
            }
        }
        List<String> patterns = splitTrimmed(corsAllowedOriginPatterns);
        if (!patterns.isEmpty() && !allowPreviewPatterns) {
            fail("pfi.cors.allowed-origin-patterns must be empty in production unless "
                + "pfi.cors.allow-preview-patterns (PFI_CORS_ALLOW_PREVIEW_PATTERNS) is explicitly true");
        }
    }

    private List<String> splitTrimmed(String value) {
        if (value == null || value.isBlank()) return List.of();
        return Arrays.stream(value.split(",")).map(String::trim).filter(v -> !v.isBlank()).toList();
    }

    private boolean isProductionProfile() {
        for (String profile : environment.getActiveProfiles()) {
            String normalized = profile.toLowerCase(Locale.ROOT);
            if (normalized.equals("production") || normalized.equals("prod")) {
                return true;
            }
        }
        return false;
    }

    private void fail(String sanitizedMessage) {
        throw new IllegalStateException(sanitizedMessage);
    }
}
