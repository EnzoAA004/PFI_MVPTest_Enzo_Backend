package ar.edu.uade.pfi.backend.auth;

import java.util.List;
import java.util.Locale;
import java.util.Optional;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

/**
 * Revalidates a request's persisted account state after JWT signature/expiry checks
 * pass, so a stale-but-unexpired access token cannot keep granting privileges a
 * deactivated/role-changed account no longer has. Split out of AuthFilter so the SQL
 * lives in PostgresAuthStoreService, not the servlet filter.
 *
 * Outside production, the JWT's own claims are trusted directly (no Postgres round
 * trip) — this preserves every existing dev/test flow, including the large body of
 * tests that mint tokens for accounts that were never persisted anywhere. In
 * production, Postgres is the sole authority: any inability to confirm the account's
 * current state (disabled persistence, connection failure, query failure) fails
 * closed as STATE_UNAVAILABLE rather than falling back to the token's claims.
 */
@Component
public class AuthAccountStateService {
    public enum Status { OK, ACCOUNT_NOT_FOUND, DEMO_BLOCKED, STATE_UNAVAILABLE }

    /**
     * claims() already carries the effective roles for this request (PENDING_APPROVAL
     * whenever verified/approved is false, regardless of whatever roles the persisted
     * row still carries) — verified/approved are kept alongside for callers that need
     * the account's raw authoritative state, not just its effective roles.
     */
    public record Resolution(Status status, TokenService.Claims claims, boolean verified, boolean approved) {
        static Resolution ok(TokenService.Claims claims, boolean verified, boolean approved) {
            return new Resolution(Status.OK, claims, verified, approved);
        }

        static Resolution of(Status status) {
            return new Resolution(status, null, false, false);
        }
    }

    private final PostgresAuthStoreService postgresAuthStore;
    private final Environment environment;

    public AuthAccountStateService(PostgresAuthStoreService postgresAuthStore, Environment environment) {
        this.postgresAuthStore = postgresAuthStore;
        this.environment = environment;
    }

    public Resolution resolve(TokenService.Claims tokenClaims) {
        if (!isProductionProfile()) {
            return Resolution.ok(tokenClaims, true, true);
        }
        if (!postgresAuthStore.enabled()) {
            return Resolution.of(Status.STATE_UNAVAILABLE);
        }
        String email = normalize(tokenClaims.email());
        Optional<DoctorAccount> accountOpt;
        try {
            accountOpt = postgresAuthStore.findByEmailForAuthorization(email);
        } catch (RuntimeException ex) {
            return Resolution.of(Status.STATE_UNAVAILABLE);
        }
        if (accountOpt.isEmpty()) {
            return Resolution.of(Status.ACCOUNT_NOT_FOUND);
        }
        DoctorAccount account = accountOpt.get();
        if (!account.id().equals(tokenClaims.subject())) {
            return Resolution.of(Status.ACCOUNT_NOT_FOUND);
        }
        if (AuthService.DEMO_ACCOUNT_EMAIL.equals(account.email())) {
            return Resolution.of(Status.DEMO_BLOCKED);
        }
        boolean active = account.verified() && account.approved();
        List<String> effectiveRoles = active ? account.roles() : List.of("PENDING_APPROVAL");
        TokenService.Claims effectiveClaims = new TokenService.Claims(account.id(), account.email(), account.fullName(), effectiveRoles);
        return Resolution.ok(effectiveClaims, account.verified(), account.approved());
    }

    private String normalize(String email) {
        return email == null ? "" : email.trim().toLowerCase(Locale.ROOT);
    }

    private boolean isProductionProfile() {
        for (String profile : environment.getActiveProfiles()) {
            String normalized = profile.toLowerCase(Locale.ROOT);
            if (normalized.equals("production") || normalized.equals("prod")) return true;
        }
        return false;
    }
}
