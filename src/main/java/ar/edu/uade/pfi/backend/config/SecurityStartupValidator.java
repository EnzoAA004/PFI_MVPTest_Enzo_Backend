package ar.edu.uade.pfi.backend.config;

import jakarta.annotation.PostConstruct;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

/**
 * Fail-closed startup gate for production profiles. This never runs (and never blocks
 * `mvn test`) unless SPRING_PROFILES_ACTIVE explicitly contains "production"/"prod" —
 * default/dev/test runs are unaffected. In production it refuses to let the context
 * finish starting if the JWT signing key is missing, blank, the repo's own demo
 * default, or too short for HS256.
 */
@Component
public class SecurityStartupValidator {
    /** Must match TokenService's own default so we can detect "nobody configured a real secret". */
    static final String DEMO_DEFAULT_SECRET = "pfi-demo-change-me-2026";
    static final int MIN_SECRET_BYTES = 32;

    private final Environment environment;
    private final String jwtSecret;

    public SecurityStartupValidator(
        Environment environment,
        @Value("${pfi.auth.jwt-secret:pfi-demo-change-me-2026}") String jwtSecret
    ) {
        this.environment = environment;
        this.jwtSecret = jwtSecret;
    }

    @PostConstruct
    void validate() {
        if (!isProductionProfile()) {
            return;
        }
        if (jwtSecret == null || jwtSecret.isBlank()) {
            throw new IllegalStateException(
                "pfi.auth.jwt-secret (PFI_AUTH_JWT_SECRET) is required in production and must not be blank");
        }
        if (DEMO_DEFAULT_SECRET.equals(jwtSecret)) {
            throw new IllegalStateException(
                "pfi.auth.jwt-secret must not use the repository's demo default value in production");
        }
        int effectiveBytes = jwtSecret.getBytes(StandardCharsets.UTF_8).length;
        if (effectiveBytes < MIN_SECRET_BYTES) {
            throw new IllegalStateException(
                "pfi.auth.jwt-secret is too short for HS256 in production; need at least "
                    + MIN_SECRET_BYTES + " bytes, got " + effectiveBytes);
        }
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
}
