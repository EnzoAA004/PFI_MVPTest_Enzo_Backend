package ar.edu.uade.pfi.backend.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import ar.edu.uade.pfi.backend.auth.dto.AuthDtos.ProfessionalActivationResponse;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

/**
 * P10-B.2: regression coverage for the root-cause bug — activation/deactivation used to
 * unconditionally replace an account's roles with a hardcoded DOCTOR+REVIEWER/ PENDING_APPROVAL
 * pair (via DoctorAccount.approve() and AuthService.activate()/ deactivate() independently
 * hardcoding the same target role lists). A professional with a single role, a restricted subset,
 * or any custom combination lost or gained roles on every activation/deactivation cycle.
 *
 * <p>Only the roles that actually exist in this project are used here: ADMIN, DOCTOR, REVIEWER,
 * PENDING_APPROVAL (see AuthFilter.KNOWN_ROLES).
 */
class AccountRolePreservationTest {
  private final PasswordHasher passwordHasher = new PasswordHasher();
  private final TokenService tokenService =
      new TokenService(new ObjectMapper(), "role-preservation-test-secret-32-bytes!!", 3600);
  private final TokenService.Claims adminClaims =
      new TokenService.Claims("admin-1", "admin@pfi.local", "Admin", List.of("ADMIN"));

  @Test
  void singleRoleAccountKeepsExactlyThatRoleAfterDeactivateAndReactivate() {
    assertRolesSurviveDeactivateThenReactivate(List.of("DOCTOR"));
  }

  @Test
  void multiRoleAccountKeepsExactlyThoseRolesAfterDeactivateAndReactivate() {
    assertRolesSurviveDeactivateThenReactivate(List.of("DOCTOR", "REVIEWER"));
  }

  /** A "restricted" professional — REVIEWER only, deliberately missing DOCTOR. */
  @Test
  void restrictedSingleReviewerRoleSurvivesDeactivateAndReactivate() {
    assertRolesSurviveDeactivateThenReactivate(List.of("REVIEWER"));
  }

  private void assertRolesSurviveDeactivateThenReactivate(List<String> originalRoles) {
    PostgresAuthStoreService store = mock(PostgresAuthStoreService.class);
    DoctorAccount account = accountWithRoles(originalRoles);
    when(store.findByEmail(account.email())).thenReturn(Optional.of(account));
    AuthService service = service(store);

    ProfessionalActivationResponse deactivateResponse =
        service.activateProfessional(adminClaims, account.email(), false);
    assertThat(deactivateResponse.roles()).containsExactlyInAnyOrderElementsOf(originalRoles);
    assertFalse(deactivateResponse.approved());
    assertThat(account.roles()).containsExactlyInAnyOrderElementsOf(originalRoles);
    assertFalse(account.approved());

    ProfessionalActivationResponse reactivateResponse =
        service.activateProfessional(adminClaims, account.email(), true);
    assertThat(reactivateResponse.roles()).containsExactlyInAnyOrderElementsOf(originalRoles);
    assertTrue(reactivateResponse.approved());
    assertThat(account.roles()).containsExactlyInAnyOrderElementsOf(originalRoles);
    assertTrue(account.approved());
  }

  @Test
  void repeatedDeactivateReactivateCyclesNeverDriftTheRoleSet() {
    PostgresAuthStoreService store = mock(PostgresAuthStoreService.class);
    List<String> originalRoles = List.of("DOCTOR", "REVIEWER");
    DoctorAccount account = accountWithRoles(originalRoles);
    when(store.findByEmail(account.email())).thenReturn(Optional.of(account));
    AuthService service = service(store);

    for (int i = 0; i < 5; i++) {
      service.activateProfessional(adminClaims, account.email(), false);
      assertThat(account.roles()).containsExactlyInAnyOrderElementsOf(originalRoles);
      service.activateProfessional(adminClaims, account.email(), true);
      assertThat(account.roles()).containsExactlyInAnyOrderElementsOf(originalRoles);
    }
  }

  /**
   * First-ever activation of a brand-new registration (roles=[PENDING_APPROVAL]) still gets the
   * documented default.
   */
  @Test
  void firstActivationOfAPendingRegistrationDefaultsToDoctorAndReviewer() {
    PostgresAuthStoreService store = mock(PostgresAuthStoreService.class);
    DoctorAccount pending = accountWithRoles(List.of("PENDING_APPROVAL"), false, false);
    when(store.findByEmail(pending.email())).thenReturn(Optional.of(pending));
    AuthService service = service(store);

    ProfessionalActivationResponse response =
        service.activateProfessional(adminClaims, pending.email(), true);

    assertThat(response.roles()).containsExactlyInAnyOrder("DOCTOR", "REVIEWER");
  }

  @Test
  void activatingAnAdminAccountIsStillProtected() {
    PostgresAuthStoreService store = mock(PostgresAuthStoreService.class);
    DoctorAccount admin = accountWithRoles(List.of("ADMIN", "DOCTOR", "REVIEWER"));
    when(store.findByEmail(admin.email())).thenReturn(Optional.of(admin));
    AuthService service = service(store);

    assertThrows(
        AdminAccountProtectedException.class,
        () -> service.activateProfessional(adminClaims, admin.email(), false));
    assertThrows(
        AdminAccountProtectedException.class,
        () -> service.activateProfessional(adminClaims, admin.email(), true));
    assertThat(admin.roles()).containsExactlyInAnyOrder("ADMIN", "DOCTOR", "REVIEWER");
  }

  @Test
  void activatingANonexistentAccountReturns404AndTouchesNothing() {
    PostgresAuthStoreService store = mock(PostgresAuthStoreService.class);
    when(store.findByEmail("ghost@hospital.example")).thenReturn(Optional.empty());
    AuthService service = service(store);

    ResponseStatusException ex =
        assertThrows(
            ResponseStatusException.class,
            () -> service.activateProfessional(adminClaims, "ghost@hospital.example", false));
    assertEquals(404, ex.getStatusCode().value());
  }

  private DoctorAccount accountWithRoles(List<String> roles) {
    return accountWithRoles(roles, true, true);
  }

  private DoctorAccount accountWithRoles(List<String> roles, boolean verified, boolean approved) {
    return new DoctorAccount(
        "account-" + roles.hashCode(),
        "Dr. Test Professional",
        "professional." + Math.abs(roles.hashCode()) + "@hospital.example",
        passwordHasher.hash("OriginalPass123!"),
        "MN-1",
        "Spine",
        "Hospital",
        roles,
        Instant.now(),
        verified,
        approved,
        false,
        true);
  }

  private AuthService service(PostgresAuthStoreService store) {
    return new AuthService(
        passwordHasher,
        tokenService,
        store,
        false,
        604800,
        new org.springframework.mock.env.MockEnvironment(),
        false,
        null);
  }
}
