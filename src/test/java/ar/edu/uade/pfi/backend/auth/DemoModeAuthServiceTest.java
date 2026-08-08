package ar.edu.uade.pfi.backend.auth;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import ar.edu.uade.pfi.backend.auth.dto.AuthDtos.PendingAuthResponse;
import ar.edu.uade.pfi.backend.auth.dto.AuthDtos.TokenResponse;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.web.server.ResponseStatusException;

/**
 * P10-A.1: demo mode must be fail-closed by default (pfi.auth.demo-enabled=false), and a demo
 * account already persisted from a previous rollout must never be able to (re)obtain a token once
 * demo mode is disabled, regardless of the roles it carries.
 */
class DemoModeAuthServiceTest {
  private final PasswordHasher passwordHasher = new PasswordHasher();
  private final TokenService tokenService =
      new TokenService(new ObjectMapper(), "test-only-secret-at-least-32-bytes-long!!", 3600);

  @Test
  void demoDisabledAnonymousSeedNeverIssuesAToken() {
    AuthService service = service(false, new MockEnvironment());
    ResponseStatusException ex =
        assertThrows(ResponseStatusException.class, service::seedDemoDoctor);
    assertEquals(404, ex.getStatusCode().value());
  }

  @Test
  void demoEnabledAndLocalProfileSeedIsPermitted() {
    PostgresAuthStoreService store = mock(PostgresAuthStoreService.class);
    AuthService service = service(true, new MockEnvironment(), store);
    TokenResponse response = service.seedDemoDoctor();
    assertTrue(response.user().roles().contains("ADMIN"));
    assertEquals(AuthService.DEMO_ACCOUNT_EMAIL, response.user().email());
  }

  @Test
  void demoEnabledButProductionProfileStillRefusesSeed() {
    MockEnvironment env = new MockEnvironment();
    env.setActiveProfiles("production");
    AuthService service = service(true, env);
    ResponseStatusException ex =
        assertThrows(ResponseStatusException.class, service::seedDemoDoctor);
    assertEquals(404, ex.getStatusCode().value());
  }

  @Test
  void persistedDemoAccountCannotLoginWhenDemoDisabled() {
    PostgresAuthStoreService store = mock(PostgresAuthStoreService.class);
    DoctorAccount persistedDemo = demoAccount();
    when(store.findByEmail(AuthService.DEMO_ACCOUNT_EMAIL)).thenReturn(Optional.of(persistedDemo));
    AuthService service = service(false, new MockEnvironment(), store);

    ResponseStatusException ex =
        assertThrows(
            ResponseStatusException.class,
            () -> service.login(AuthService.DEMO_ACCOUNT_EMAIL, "Demo1234!"));
    assertEquals(401, ex.getStatusCode().value());
    assertTrue(!ex.getReason().toLowerCase().contains("demo"));
  }

  @Test
  void persistedDemoAccountCannotRefreshWhenDemoDisabled() {
    PostgresAuthStoreService store = mock(PostgresAuthStoreService.class);
    when(store.findEmailByRefreshToken("demo-refresh-token"))
        .thenReturn(Optional.of(AuthService.DEMO_ACCOUNT_EMAIL));
    AuthService service = service(false, new MockEnvironment(), store);

    ResponseStatusException ex =
        assertThrows(ResponseStatusException.class, () -> service.refresh("demo-refresh-token"));
    assertEquals(401, ex.getStatusCode().value());
    verify(store, times(1)).revokeRefreshToken("demo-refresh-token");
  }

  @Test
  void persistedDemoAccountWorksWhenDemoEnabledLocally() {
    PostgresAuthStoreService store = mock(PostgresAuthStoreService.class);
    when(store.findByEmail(AuthService.DEMO_ACCOUNT_EMAIL)).thenReturn(Optional.of(demoAccount()));
    AuthService service = service(true, new MockEnvironment(), store);

    Object result = service.login(AuthService.DEMO_ACCOUNT_EMAIL, "Demo1234!");
    assertTrue(result instanceof TokenResponse || result instanceof PendingAuthResponse);
  }

  @Test
  void normalProfessionalAccountIsUnaffectedByDemoDisabled() {
    PostgresAuthStoreService store = mock(PostgresAuthStoreService.class);
    DoctorAccount professional =
        new DoctorAccount(
            "prof-1",
            "Dr. Real",
            "real.doctor@hospital.example",
            passwordHasher.hash("RealPass123!"),
            "MN-1",
            "Spine",
            "Hospital",
            List.of("DOCTOR", "REVIEWER"),
            Instant.now(),
            true,
            true,
            false,
            true);
    when(store.findByEmail("real.doctor@hospital.example")).thenReturn(Optional.of(professional));
    AuthService service = service(false, new MockEnvironment(), store);

    Object result = service.login("real.doctor@hospital.example", "RealPass123!");
    assertTrue(result instanceof TokenResponse);
  }

  @Test
  void startupRevokesLingeringDemoRefreshTokensWhenDemoDisabled() {
    PostgresAuthStoreService store = mock(PostgresAuthStoreService.class);
    service(false, new MockEnvironment(), store).revokeDemoSessionsIfDemoDisabled();
    verify(store).revokeRefreshTokensForEmail(AuthService.DEMO_ACCOUNT_EMAIL);
  }

  @Test
  void startupDoesNotRevokeAnythingWhenDemoEnabledLocally() {
    PostgresAuthStoreService store = mock(PostgresAuthStoreService.class);
    service(true, new MockEnvironment(), store).revokeDemoSessionsIfDemoDisabled();
    verify(store, never()).revokeRefreshTokensForEmail(any());
  }

  private DoctorAccount demoAccount() {
    return new DoctorAccount(
        "demo-1",
        "Dra. Demo Reviewer",
        AuthService.DEMO_ACCOUNT_EMAIL,
        passwordHasher.hash("Demo1234!"),
        "MN-DEMO-2026",
        "Radiologia",
        "PFI Academic Lab",
        List.of("ADMIN", "DOCTOR", "REVIEWER"),
        Instant.now(),
        true,
        true,
        false,
        true);
  }

  private AuthService service(boolean demoEnabled, MockEnvironment environment) {
    return service(demoEnabled, environment, mock(PostgresAuthStoreService.class));
  }

  private AuthService service(
      boolean demoEnabled, MockEnvironment environment, PostgresAuthStoreService store) {
    return new AuthService(
        passwordHasher, tokenService, store, false, 604800, environment, demoEnabled, null);
  }
}
