package ar.edu.uade.pfi.backend.auth;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
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

/**
 * Real-Postgres coverage for the idempotency and persistence claims that a mocked store can't
 * verify.
 */
@Testcontainers(disabledWithoutDocker = true)
class AdminBootstrapPostgresIntegrationTest {
  @Container
  static final PostgreSQLContainer<?> postgres =
      new PostgreSQLContainer<>("postgres:16-alpine")
          .withDatabaseName("pfi_p10a2")
          .withUsername("pfi")
          .withPassword("pfi");

  private static final String EMAIL = "real.admin@hospital.example";
  private static final String PASSWORD = "a-strong-random-password-16plus";

  private PostgresAuthStoreService store;
  private final PasswordHasher passwordHasher = new PasswordHasher();

  @BeforeEach
  void setUp() throws Exception {
    store =
        new PostgresAuthStoreService(
            "postgres",
            postgres.getJdbcUrl()
                + "&user="
                + postgres.getUsername()
                + "&password="
                + postgres.getPassword());
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

  // ---- P10-A.2.2 §4/C: findNonDemoAdmin/hasNonDemoAdmin must accept only an exact,
  // active ADMIN — never SUPERADMIN, an unverified/unapproved row, or the demo account.

  @Test
  void bootstrapIsSkippedWhenAnExactActiveAdminAlreadyExists() {
    seed("existing.admin@hospital.example", List.of("ADMIN", "DOCTOR", "REVIEWER"), true, true);

    assertEquals(true, store.hasNonDemoAdmin(AuthService.DEMO_ACCOUNT_EMAIL));
    AdminBootstrapService bootstrap = service();
    bootstrap.bootstrap();
    assertEquals(1, store.listAccounts().size());
  }

  @Test
  void unapprovedExactAdminDoesNotCountAsAnExistingAdmin() {
    seed("unapproved.admin@hospital.example", List.of("ADMIN", "DOCTOR", "REVIEWER"), true, false);

    assertEquals(false, store.hasNonDemoAdmin(AuthService.DEMO_ACCOUNT_EMAIL));
  }

  @Test
  void unverifiedExactAdminDoesNotCountAsAnExistingAdmin() {
    seed("unverified.admin@hospital.example", List.of("ADMIN", "DOCTOR", "REVIEWER"), false, true);

    assertEquals(false, store.hasNonDemoAdmin(AuthService.DEMO_ACCOUNT_EMAIL));
  }

  @Test
  void superAdminRoleDoesNotCountAsAnExistingAdmin() {
    seed("superadmin@hospital.example", List.of("SUPERADMIN", "DOCTOR", "REVIEWER"), true, true);

    assertEquals(false, store.hasNonDemoAdmin(AuthService.DEMO_ACCOUNT_EMAIL));
  }

  @Test
  void demoAccountAdminDoesNotCountAsAnExistingAdmin() {
    seed(AuthService.DEMO_ACCOUNT_EMAIL, List.of("ADMIN", "DOCTOR", "REVIEWER"), true, true);

    assertEquals(false, store.hasNonDemoAdmin(AuthService.DEMO_ACCOUNT_EMAIL));
  }

  @Test
  void sqlErrorDuringAdminLookupFailsStartupClosed() {
    PostgresAuthStoreService brokenStore =
        new PostgresAuthStoreService("postgres", "jdbc:postgresql://localhost:1/does-not-exist");
    AdminBootstrapService bootstrap =
        new AdminBootstrapService(
            brokenStore,
            passwordHasher,
            production(),
            true,
            EMAIL,
            PASSWORD,
            "Dr. Real Admin",
            "MN-REAL-001",
            "Hospital Real",
            "another-strong-random-jwt-secret-value");

    assertThrows(IllegalStateException.class, bootstrap::bootstrap);
  }

  private void seed(String email, List<String> roles, boolean verified, boolean approved) {
    store.saveAccount(
        new DoctorAccount(
            java.util.UUID.randomUUID().toString(),
            "Dr. Existing",
            email,
            passwordHasher.hash(PASSWORD),
            "MN-1",
            "Spine",
            "Hospital",
            roles,
            java.time.Instant.now(),
            verified,
            approved,
            false,
            true));
  }

  private MockEnvironment production() {
    MockEnvironment env = new MockEnvironment();
    env.setActiveProfiles("production");
    return env;
  }

  private void truncate() throws Exception {
    try (var connection =
            DriverManager.getConnection(
                postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
        var statement = connection.createStatement()) {
      statement.execute("TRUNCATE auth_refresh_tokens, doctor_accounts RESTART IDENTITY CASCADE");
    }
  }

  private AdminBootstrapService service() {
    MockEnvironment env = new MockEnvironment();
    env.setActiveProfiles("production");
    return new AdminBootstrapService(
        store,
        passwordHasher,
        env,
        true,
        EMAIL,
        PASSWORD,
        "Dr. Real Admin",
        "MN-REAL-001",
        "Hospital Real",
        "another-strong-random-jwt-secret-value");
  }
}
