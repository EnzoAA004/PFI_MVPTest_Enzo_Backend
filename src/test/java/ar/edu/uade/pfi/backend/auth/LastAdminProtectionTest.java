package ar.edu.uade.pfi.backend.auth;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

/**
 * P10-A.2.2: the professional activation/approval flow now rejects ANY ADMIN account up
 * front (AdminAccountProtectedException, in AuthService.setProfessionalActivation),
 * before it can ever reach the older last-ADMIN-standing check further down in
 * deactivate()/isLastNonDemoAdmin() — so every scenario that used to surface
 * LastAdminProtectionException through this flow now surfaces
 * AdminAccountProtectedException instead. isLastNonDemoAdmin/countOtherNonDemoAdmins/
 * LastAdminProtectionException themselves are kept as defense-in-depth for any other
 * future flow that could deactivate an ADMIN account outside the professional-activation
 * path — they are simply unreachable from this flow now.
 */
class LastAdminProtectionTest {
    private final PasswordHasher passwordHasher = new PasswordHasher();
    private final TokenService tokenService = new TokenService(new ObjectMapper(), "test-only-secret-at-least-32-bytes-long!!", 3600);
    private final TokenService.Claims adminClaims = new TokenService.Claims("admin-1", "admin@pfi.local", "Admin", List.of("ADMIN"));

    @Test
    void theOnlyNonDemoAdminCannotBeDeactivatedThroughTheProfessionalFlow() {
        PostgresAuthStoreService store = mock(PostgresAuthStoreService.class);
        DoctorAccount onlyAdmin = admin("only-admin@pfi.local");
        when(store.findByEmail(onlyAdmin.email())).thenReturn(Optional.of(onlyAdmin));
        when(store.enabled()).thenReturn(true);
        AuthService service = service(store);

        AdminAccountProtectedException ex = assertThrows(AdminAccountProtectedException.class,
            () -> service.activateProfessional(adminClaims, onlyAdmin.email(), false));
        assertEquals("ADMIN_ACCOUNT_PROTECTED", ex.code());
        assertEquals(409, ex.status().value());
    }

    @Test
    void withTwoAdminsNeitherCanBeDeactivatedThroughTheProfessionalFlow() {
        PostgresAuthStoreService store = mock(PostgresAuthStoreService.class);
        DoctorAccount first = admin("first-admin@pfi.local");
        when(store.findByEmail(first.email())).thenReturn(Optional.of(first));
        when(store.enabled()).thenReturn(true);
        AuthService service = service(store);

        assertThrows(AdminAccountProtectedException.class,
            () -> service.activateProfessional(adminClaims, first.email(), false));
    }

    @Test
    void aRealAdminAccountIsProtectedRegardlessOfHowManyOtherAdminsExist() {
        PostgresAuthStoreService store = mock(PostgresAuthStoreService.class);
        DoctorAccount realAdmin = admin("real-admin@pfi.local");
        when(store.findByEmail(realAdmin.email())).thenReturn(Optional.of(realAdmin));
        when(store.enabled()).thenReturn(true);
        AuthService service = service(store);

        assertThrows(AdminAccountProtectedException.class,
            () -> service.activateProfessional(adminClaims, realAdmin.email(), false));
    }

    @Test
    void aNormalProfessionalDeactivationIsNeverAffectedByAdminProtection() {
        PostgresAuthStoreService store = mock(PostgresAuthStoreService.class);
        DoctorAccount professional = new DoctorAccount(
            "doc-1", "Dr. Regular", "regular.doc@hospital.example", passwordHasher.hash("OriginalPass123!"),
            "MN-2", "Spine", "Hospital", List.of("DOCTOR", "REVIEWER"), Instant.now(), true, true, false, true
        );
        when(store.findByEmail(professional.email())).thenReturn(Optional.of(professional));
        when(store.enabled()).thenReturn(true);
        AuthService service = service(store);

        var response = service.activateProfessional(adminClaims, professional.email(), false);

        assertEquals("deactivated", response.status());
    }

    @Test
    void adminDeactivationAttemptNeverEvenQueriesTheLastAdminCount() {
        PostgresAuthStoreService store = mock(PostgresAuthStoreService.class);
        DoctorAccount onlyAdmin = admin("only-admin-2@pfi.local");
        when(store.findByEmail(onlyAdmin.email())).thenReturn(Optional.of(onlyAdmin));
        when(store.enabled()).thenReturn(true);
        AuthService service = service(store);

        assertThrows(AdminAccountProtectedException.class,
            () -> service.activateProfessional(adminClaims, onlyAdmin.email(), false));
        org.mockito.Mockito.verify(store, org.mockito.Mockito.never())
            .countActiveNonDemoAdminsExcluding(org.mockito.ArgumentMatchers.any());
    }

    private DoctorAccount admin(String email) {
        return new DoctorAccount(
            "id-" + email, "Dr. Admin", email, passwordHasher.hash("OriginalPass123!"),
            "MN-ADMIN", "Admin", "PFI", List.of("ADMIN", "DOCTOR", "REVIEWER"), Instant.now(), true, true, false, true
        );
    }

    private AuthService service(PostgresAuthStoreService store) {
        return new AuthService(passwordHasher, tokenService, store, false, 604800, new MockEnvironment(), false, null);
    }
}
