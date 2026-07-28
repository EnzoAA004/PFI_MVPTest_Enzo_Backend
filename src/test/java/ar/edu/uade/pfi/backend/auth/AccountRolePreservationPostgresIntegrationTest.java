package ar.edu.uade.pfi.backend.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.DriverManager;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * P10-B.2: real-Postgres coverage for role preservation — a mocked
 * PostgresAuthStoreService can prove what AuthService *sends* to persistence, but not
 * that the row genuinely comes back with the same roles after a real round trip. This
 * reloads the account from the database after each operation instead of trusting the
 * returned DTO.
 */
@Testcontainers
class AccountRolePreservationPostgresIntegrationTest {
    @Container
    static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine")
        .withDatabaseName("pfi_p10b2")
        .withUsername("pfi")
        .withPassword("pfi");

    private static final String PASSWORD = "OriginalPass123!";

    private PostgresAuthStoreService store;
    private AuthService authService;
    private final PasswordHasher passwordHasher = new PasswordHasher();
    private final TokenService.Claims adminClaims = new TokenService.Claims("admin-1", "admin@pfi.local", "Admin", List.of("ADMIN"));

    @BeforeEach
    void setUp() throws Exception {
        store = new PostgresAuthStoreService("postgres", postgres.getJdbcUrl() + "&user=" + postgres.getUsername() + "&password=" + postgres.getPassword());
        truncate();
        TokenService tokenService = new TokenService(new ObjectMapper(), "postgres-role-preservation-secret-32b!!", 3600);
        authService = new AuthService(passwordHasher, tokenService, store, false, 604800, new MockEnvironment(), false, null);
    }

    @Test
    void multiRoleAccountRoundTripsThroughPostgresUnchangedAfterDeactivateAndReactivate() {
        String email = seedAccount(List.of("DOCTOR", "REVIEWER"));

        authService.activateProfessional(adminClaims, email, false);
        DoctorAccount afterDeactivate = reload(email);
        assertThat(afterDeactivate.roles()).containsExactlyInAnyOrder("DOCTOR", "REVIEWER");
        assertFalse(afterDeactivate.approved());

        authService.activateProfessional(adminClaims, email, true);
        DoctorAccount afterReactivate = reload(email);
        assertThat(afterReactivate.roles()).containsExactlyInAnyOrder("DOCTOR", "REVIEWER");
        assertTrue(afterReactivate.approved());
    }

    @Test
    void restrictedSingleRoleAccountRoundTripsThroughPostgresUnchanged() {
        String email = seedAccount(List.of("REVIEWER"));

        authService.activateProfessional(adminClaims, email, false);
        assertThat(reload(email).roles()).containsExactlyInAnyOrder("REVIEWER");

        authService.activateProfessional(adminClaims, email, true);
        assertThat(reload(email).roles()).containsExactlyInAnyOrder("REVIEWER");
    }

    @Test
    void deactivatedAccountRefreshTokenIsRevokedInPostgresAndCannotIssueANewAccessToken() {
        String email = seedAccount(List.of("DOCTOR", "REVIEWER"));
        var loginResult = authService.login(email, PASSWORD);
        String refreshToken = ((ar.edu.uade.pfi.backend.auth.dto.AuthDtos.TokenResponse) loginResult).refreshToken();
        assertTrue(store.findEmailByRefreshToken(refreshToken).isPresent());

        authService.activateProfessional(adminClaims, email, false);

        assertFalse(store.findEmailByRefreshToken(refreshToken).isPresent());
        org.springframework.web.server.ResponseStatusException ex = org.junit.jupiter.api.Assertions.assertThrows(
            org.springframework.web.server.ResponseStatusException.class,
            () -> authService.refresh(refreshToken)
        );
        org.junit.jupiter.api.Assertions.assertEquals(401, ex.getStatusCode().value());
    }

    private DoctorAccount reload(String email) {
        Optional<DoctorAccount> reloaded = store.findByEmail(email);
        assertTrue(reloaded.isPresent(), "account must still be persisted");
        return reloaded.get();
    }

    private String seedAccount(List<String> roles) {
        String email = "professional." + UUID.randomUUID() + "@hospital.example";
        DoctorAccount account = new DoctorAccount(
            UUID.randomUUID().toString(), "Dr. Test Professional", email,
            passwordHasher.hash(PASSWORD), "MN-1", "Spine", "Hospital",
            roles, Instant.now(), true, true, false, true
        );
        store.saveAccount(account);
        return email;
    }

    private void truncate() throws Exception {
        try (var connection = DriverManager.getConnection(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
             var statement = connection.createStatement()) {
            statement.execute("TRUNCATE auth_refresh_tokens, doctor_accounts RESTART IDENTITY CASCADE");
        }
    }
}
