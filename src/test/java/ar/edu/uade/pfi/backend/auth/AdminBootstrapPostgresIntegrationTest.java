package ar.edu.uade.pfi.backend.auth;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.sql.DriverManager;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/** Real-Postgres coverage for the idempotency and persistence claims that a mocked store can't verify. */
@Testcontainers
class AdminBootstrapPostgresIntegrationTest {
    @Container
    static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine")
        .withDatabaseName("pfi_p10a2")
        .withUsername("pfi")
        .withPassword("pfi");

    private static final String EMAIL = "real.admin@hospital.example";
    private static final String PASSWORD = "a-strong-random-password-16plus";

    private PostgresAuthStoreService store;
    private final PasswordHasher passwordHasher = new PasswordHasher();

    @BeforeEach
    void setUp() throws Exception {
        store = new PostgresAuthStoreService("postgres", postgres.getJdbcUrl() + "&user=" + postgres.getUsername() + "&password=" + postgres.getPassword());
        truncate();
    }

    @Test
    void bootstrapPersistsAdminAndSecondStartupIsIdempotent() {
        AdminBootstrapService first = service();
        first.bootstrap();

        Optional<DoctorAccount> afterFirst = store.findByEmail(EMAIL);
        assertTrue(afterFirst.isPresent());
        assertEquals(List.of("ADMIN", "DOCTOR", "REVIEWER"), afterFirst.get().roles());
        assertTrue(afterFirst.get().verified());
        assertTrue(afterFirst.get().approved());
        String id = afterFirst.get().id();
        String hash = afterFirst.get().passwordHash();

        AdminBootstrapService second = service();
        second.bootstrap();

        Optional<DoctorAccount> afterSecond = store.findByEmail(EMAIL);
        assertTrue(afterSecond.isPresent());
        assertEquals(id, afterSecond.get().id());
        assertEquals(hash, afterSecond.get().passwordHash());
        assertEquals(1, store.listAccounts().size());
    }

    @Test
    void hasNonDemoAdminReflectsPersistedState() {
        assertEquals(false, store.hasNonDemoAdmin(AuthService.DEMO_ACCOUNT_EMAIL));
        service().bootstrap();
        assertEquals(true, store.hasNonDemoAdmin(AuthService.DEMO_ACCOUNT_EMAIL));
    }

    private void truncate() throws Exception {
        try (var connection = DriverManager.getConnection(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
             var statement = connection.createStatement()) {
            statement.execute("TRUNCATE auth_refresh_tokens, doctor_accounts RESTART IDENTITY CASCADE");
        }
    }

    private AdminBootstrapService service() {
        MockEnvironment env = new MockEnvironment();
        env.setActiveProfiles("production");
        return new AdminBootstrapService(
            store, passwordHasher, env, true, EMAIL, PASSWORD,
            "Dr. Real Admin", "MN-REAL-001", "Hospital Real",
            "another-strong-random-jwt-secret-value"
        );
    }
}
