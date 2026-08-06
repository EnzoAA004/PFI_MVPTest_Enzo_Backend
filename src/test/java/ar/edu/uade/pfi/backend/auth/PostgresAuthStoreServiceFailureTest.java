package ar.edu.uade.pfi.backend.auth;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.sql.DriverManager;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * P10-A.2.1 §13: fail-closed behavior for the strict PostgresAuthStoreService methods —
 * Postgres-disabled and real-connection-failure both surface as thrown exceptions
 * (never a silent empty/false answer), and the deactivation+revocation transaction
 * never leaves a partial state behind, with no JDBC URL or credentials leaked into any
 * exception message.
 */
@Testcontainers(disabledWithoutDocker = true)
class PostgresAuthStoreServiceFailureTest {
    @Container
    static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine")
        .withDatabaseName("pfi_p10a21_fail")
        .withUsername("pfi")
        .withPassword("pfi");

    private PostgresAuthStoreService store;
    private final PasswordHasher passwordHasher = new PasswordHasher();

    @BeforeEach
    void setUp() throws Exception {
        store = new PostgresAuthStoreService("postgres", postgres.getJdbcUrl() + "&user=" + postgres.getUsername() + "&password=" + postgres.getPassword());
        try (var connection = DriverManager.getConnection(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
             var statement = connection.createStatement()) {
            statement.execute("TRUNCATE auth_refresh_tokens, doctor_accounts RESTART IDENTITY CASCADE");
        }
    }

    @Test
    void postgresDisabledMakesFindByEmailForAuthorizationThrow() {
        PostgresAuthStoreService disabled = new PostgresAuthStoreService("memory", "");
        assertThrows(IllegalStateException.class, () -> disabled.findByEmailForAuthorization("doc@hospital.example"));
    }

    @Test
    void postgresDisabledMakesCountActiveNonDemoAdminsThrow() {
        PostgresAuthStoreService disabled = new PostgresAuthStoreService("memory", "");
        assertThrows(IllegalStateException.class, () -> disabled.countActiveNonDemoAdminsExcluding("nobody@hospital.example"));
    }

    @Test
    void postgresDisabledMakesDeactivateAndRevokeThrow() {
        PostgresAuthStoreService disabled = new PostgresAuthStoreService("memory", "");
        assertThrows(IllegalStateException.class,
            () -> disabled.deactivateProfessionalAndRevokeSessions("doc@hospital.example", true, List.of("PENDING_APPROVAL")));
    }

    @Test
    void connectionFailureNeverLeaksJdbcUrlOrCredentials() {
        PostgresAuthStoreService brokenConnection = new PostgresAuthStoreServiceTestHelper().withBrokenConnection();
        IllegalStateException ex = assertThrows(IllegalStateException.class,
            () -> brokenConnection.findByEmailForAuthorization("doc@hospital.example"));
        String fullMessage = ex.getMessage() + " " + (ex.getCause() == null ? "" : ex.getCause().getMessage());
        assertFalse(fullMessage.toLowerCase().contains("password="));
        assertFalse(fullMessage.toLowerCase().contains("pfi_bad_password"));
    }

    @Test
    void deactivatingAnUnknownEmailThrowsAndDoesNotLeakJdbcDetails() {
        IllegalStateException ex = assertThrows(IllegalStateException.class,
            () -> store.deactivateProfessionalAndRevokeSessions("nobody@hospital.example", true, List.of("PENDING_APPROVAL")));
        assertFalse(ex.getMessage().toLowerCase().contains("jdbc:postgresql"));
        assertFalse(ex.getMessage().toLowerCase().contains("password"));
    }

    @Test
    void successfulDeactivationRevokesRefreshTokensInTheSameTransaction() throws Exception {
        DoctorAccount account = new DoctorAccount(
            "doc-1", "Dr Doc", "doc.tx@hospital.example", passwordHasher.hash("OriginalPass123!"),
            "MN-1", "Spine", "Hospital", List.of("DOCTOR", "REVIEWER"), Instant.now(), true, true, false, true
        );
        store.saveAccount(account);
        store.saveRefreshToken("refresh-token-1", account.email(), Instant.now().plusSeconds(3600));
        assertTrue(store.findEmailByRefreshToken("refresh-token-1").isPresent());

        store.deactivateProfessionalAndRevokeSessions(account.email(), true, List.of("PENDING_APPROVAL"));

        assertTrue(store.findEmailByRefreshToken("refresh-token-1").isEmpty());
        Optional<DoctorAccount> reloaded = store.findByEmail(account.email());
        assertTrue(reloaded.isPresent());
        assertFalse(reloaded.get().approved());
    }

    /** Deliberately points at a valid host/port with a wrong password to force a real JDBC auth failure. */
    private static class PostgresAuthStoreServiceTestHelper {
        PostgresAuthStoreService withBrokenConnection() {
            String badJdbcUrl = "jdbc:postgresql://" + postgres.getHost() + ":" + postgres.getFirstMappedPort()
                + "/" + postgres.getDatabaseName() + "?user=" + postgres.getUsername() + "&password=pfi_bad_password";
            return new PostgresAuthStoreService("postgres", badJdbcUrl);
        }
    }
}
