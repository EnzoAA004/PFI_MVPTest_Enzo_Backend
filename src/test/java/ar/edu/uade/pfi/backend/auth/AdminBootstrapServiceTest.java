package ar.edu.uade.pfi.backend.auth;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

class AdminBootstrapServiceTest {
  private static final String VALID_EMAIL = "real.admin@hospital.example";
  private static final String VALID_PASSWORD = "a-strong-random-password-16plus";
  private static final String VALID_NAME = "Dr. Real Admin";
  private static final String VALID_LICENSE = "MN-REAL-001";
  private static final String VALID_INSTITUTION = "Hospital Real";
  private static final String JWT_SECRET = "another-strong-random-jwt-secret-value";

  private final PasswordHasher passwordHasher = new PasswordHasher();

  @Test
  void productionWithBootstrapDisabledAndExistingAdminStartsCleanly() {
    PostgresAuthStoreService store = mock(PostgresAuthStoreService.class);
    when(store.hasNonDemoAdmin(AuthService.DEMO_ACCOUNT_EMAIL)).thenReturn(true);

    assertDoesNotThrow(() -> service(store, false, VALID_EMAIL, VALID_PASSWORD).bootstrap());
    verify(store, never()).createBootstrapAdmin(any());
  }

  @Test
  void productionWithBootstrapDisabledAndNoAdminFailsStartup() {
    PostgresAuthStoreService store = mock(PostgresAuthStoreService.class);
    when(store.hasNonDemoAdmin(AuthService.DEMO_ACCOUNT_EMAIL)).thenReturn(false);

    IllegalStateException ex =
        assertThrows(IllegalStateException.class, () -> service(store, false, "", "").bootstrap());
    assertDoesNotThrow(() -> ex.getMessage().length()); // sanitized, non-null message
  }

  @Test
  void productionWithBootstrapEnabledAndValidConfigCreatesAdmin() {
    PostgresAuthStoreService store = mock(PostgresAuthStoreService.class);
    when(store.hasNonDemoAdmin(AuthService.DEMO_ACCOUNT_EMAIL)).thenReturn(false);
    when(store.createBootstrapAdmin(any())).thenReturn(persistedAdmin());
    when(store.findByEmail(VALID_EMAIL)).thenReturn(Optional.of(persistedAdmin()));

    service(store, true, VALID_EMAIL, VALID_PASSWORD).bootstrap();

    verify(store).createBootstrapAdmin(any());
  }

  @Test
  void createdAdminHasExpectedRolesAndFlags() {
    PostgresAuthStoreService store = mock(PostgresAuthStoreService.class);
    when(store.hasNonDemoAdmin(AuthService.DEMO_ACCOUNT_EMAIL)).thenReturn(false);
    when(store.createBootstrapAdmin(any())).thenReturn(persistedAdmin());
    when(store.findByEmail(VALID_EMAIL)).thenReturn(Optional.of(persistedAdmin()));

    service(store, true, VALID_EMAIL, VALID_PASSWORD).bootstrap();

    org.mockito.ArgumentCaptor<DoctorAccount> captor =
        org.mockito.ArgumentCaptor.forClass(DoctorAccount.class);
    verify(store).createBootstrapAdmin(captor.capture());
    DoctorAccount created = captor.getValue();
    org.junit.jupiter.api.Assertions.assertEquals(
        List.of("ADMIN", "DOCTOR", "REVIEWER"), created.roles());
    org.junit.jupiter.api.Assertions.assertTrue(created.verified());
    org.junit.jupiter.api.Assertions.assertTrue(created.approved());
  }

  @Test
  void secondStartupIsIdempotentAndNeverCallsCreateAgain() {
    PostgresAuthStoreService store = mock(PostgresAuthStoreService.class);
    // First startup: no admin yet.
    when(store.hasNonDemoAdmin(AuthService.DEMO_ACCOUNT_EMAIL)).thenReturn(false, true);
    when(store.createBootstrapAdmin(any())).thenReturn(persistedAdmin());
    when(store.findByEmail(VALID_EMAIL)).thenReturn(Optional.of(persistedAdmin()));

    AdminBootstrapService first = service(store, true, VALID_EMAIL, VALID_PASSWORD);
    first.bootstrap();
    AdminBootstrapService second = service(store, true, VALID_EMAIL, VALID_PASSWORD);
    second.bootstrap();

    verify(store, org.mockito.Mockito.times(1)).createBootstrapAdmin(any());
  }

  @Test
  void demoEmailIsRejected() {
    PostgresAuthStoreService store = mock(PostgresAuthStoreService.class);
    when(store.hasNonDemoAdmin(AuthService.DEMO_ACCOUNT_EMAIL)).thenReturn(false);
    assertThrows(
        IllegalStateException.class,
        () -> service(store, true, AuthService.DEMO_ACCOUNT_EMAIL, VALID_PASSWORD).bootstrap());
  }

  @Test
  void invalidEmailIsRejected() {
    PostgresAuthStoreService store = mock(PostgresAuthStoreService.class);
    when(store.hasNonDemoAdmin(AuthService.DEMO_ACCOUNT_EMAIL)).thenReturn(false);
    assertThrows(
        IllegalStateException.class,
        () -> service(store, true, "not-an-email", VALID_PASSWORD).bootstrap());
  }

  @Test
  void demoPasswordIsRejected() {
    PostgresAuthStoreService store = mock(PostgresAuthStoreService.class);
    when(store.hasNonDemoAdmin(AuthService.DEMO_ACCOUNT_EMAIL)).thenReturn(false);
    assertThrows(
        IllegalStateException.class,
        () -> service(store, true, VALID_EMAIL, "Demo1234!").bootstrap());
  }

  @Test
  void shortPasswordIsRejected() {
    PostgresAuthStoreService store = mock(PostgresAuthStoreService.class);
    when(store.hasNonDemoAdmin(AuthService.DEMO_ACCOUNT_EMAIL)).thenReturn(false);
    assertThrows(
        IllegalStateException.class,
        () -> service(store, true, VALID_EMAIL, "short123").bootstrap());
  }

  @Test
  void passwordEqualToJwtSecretIsRejected() {
    PostgresAuthStoreService store = mock(PostgresAuthStoreService.class);
    when(store.hasNonDemoAdmin(AuthService.DEMO_ACCOUNT_EMAIL)).thenReturn(false);
    assertThrows(
        IllegalStateException.class,
        () -> service(store, true, VALID_EMAIL, JWT_SECRET).bootstrap());
  }

  @Test
  void emptyFullNameIsRejected() {
    PostgresAuthStoreService store = mock(PostgresAuthStoreService.class);
    when(store.hasNonDemoAdmin(AuthService.DEMO_ACCOUNT_EMAIL)).thenReturn(false);
    assertThrows(
        IllegalStateException.class,
        () ->
            new AdminBootstrapService(
                    store,
                    passwordHasher,
                    production(),
                    true,
                    VALID_EMAIL,
                    VALID_PASSWORD,
                    "",
                    VALID_LICENSE,
                    VALID_INSTITUTION,
                    JWT_SECRET)
                .bootstrap());
  }

  @Test
  void emptyLicenseIsRejected() {
    PostgresAuthStoreService store = mock(PostgresAuthStoreService.class);
    when(store.hasNonDemoAdmin(AuthService.DEMO_ACCOUNT_EMAIL)).thenReturn(false);
    assertThrows(
        IllegalStateException.class,
        () ->
            new AdminBootstrapService(
                    store,
                    passwordHasher,
                    production(),
                    true,
                    VALID_EMAIL,
                    VALID_PASSWORD,
                    VALID_NAME,
                    "",
                    VALID_INSTITUTION,
                    JWT_SECRET)
                .bootstrap());
  }

  @Test
  void emptyInstitutionIsRejected() {
    PostgresAuthStoreService store = mock(PostgresAuthStoreService.class);
    when(store.hasNonDemoAdmin(AuthService.DEMO_ACCOUNT_EMAIL)).thenReturn(false);
    assertThrows(
        IllegalStateException.class,
        () ->
            new AdminBootstrapService(
                    store,
                    passwordHasher,
                    production(),
                    true,
                    VALID_EMAIL,
                    VALID_PASSWORD,
                    VALID_NAME,
                    VALID_LICENSE,
                    "",
                    JWT_SECRET)
                .bootstrap());
  }

  @Test
  void postgresDisabledSurfacesAsStartupFailure() {
    PostgresAuthStoreService store = mock(PostgresAuthStoreService.class);
    when(store.hasNonDemoAdmin(anyString()))
        .thenThrow(new IllegalStateException("Postgres auth persistence is not enabled"));

    assertThrows(
        IllegalStateException.class,
        () -> service(store, true, VALID_EMAIL, VALID_PASSWORD).bootstrap());
  }

  @Test
  void connectionFailureSurfacesAsStartupFailure() {
    PostgresAuthStoreService store = mock(PostgresAuthStoreService.class);
    when(store.hasNonDemoAdmin(anyString()))
        .thenThrow(
            new IllegalStateException("Could not query for an existing production ADMIN account"));

    assertThrows(
        IllegalStateException.class,
        () -> service(store, true, VALID_EMAIL, VALID_PASSWORD).bootstrap());
  }

  @Test
  void persistenceErrorSurfacesAsStartupFailure() {
    PostgresAuthStoreService store = mock(PostgresAuthStoreService.class);
    when(store.hasNonDemoAdmin(AuthService.DEMO_ACCOUNT_EMAIL)).thenReturn(false);
    when(store.createBootstrapAdmin(any()))
        .thenThrow(new IllegalStateException("Could not persist the bootstrap ADMIN account"));

    assertThrows(
        IllegalStateException.class,
        () -> service(store, true, VALID_EMAIL, VALID_PASSWORD).bootstrap());
  }

  @Test
  void nonProductionProfileNeverRunsAutomatically() {
    PostgresAuthStoreService store = mock(PostgresAuthStoreService.class);
    AdminBootstrapService service =
        new AdminBootstrapService(
            store, passwordHasher, new MockEnvironment(), false, "", "", "", "", "", JWT_SECRET);

    assertDoesNotThrow(service::bootstrap);
    verifyNoInteractions(store);
  }

  private DoctorAccount persistedAdmin() {
    return new DoctorAccount(
        "bootstrap-1",
        VALID_NAME,
        VALID_EMAIL,
        passwordHasher.hash(VALID_PASSWORD),
        VALID_LICENSE,
        "",
        VALID_INSTITUTION,
        List.of("ADMIN", "DOCTOR", "REVIEWER"),
        Instant.now(),
        true,
        true,
        false,
        false);
  }

  private MockEnvironment production() {
    MockEnvironment env = new MockEnvironment();
    env.setActiveProfiles("production");
    return env;
  }

  private AdminBootstrapService service(
      PostgresAuthStoreService store, boolean bootstrapEnabled, String email, String password) {
    return new AdminBootstrapService(
        store,
        passwordHasher,
        production(),
        bootstrapEnabled,
        email,
        password,
        VALID_NAME,
        VALID_LICENSE,
        VALID_INSTITUTION,
        JWT_SECRET);
  }
}
