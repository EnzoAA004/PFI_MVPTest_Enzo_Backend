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

class LastAdminProtectionTest {
    private final PasswordHasher passwordHasher = new PasswordHasher();
    private final TokenService tokenService = new TokenService(new ObjectMapper(), "test-only-secret-at-least-32-bytes-long!!", 3600);
    private final TokenService.Claims adminClaims = new TokenService.Claims("admin-1", "admin@pfi.local", "Admin", List.of("ADMIN"));

    @Test
    void theOnlyNonDemoAdminCannotBeDeactivated() {
        PostgresAuthStoreService store = mock(PostgresAuthStoreService.class);
        DoctorAccount onlyAdmin = admin("only-admin@pfi.local");
        when(store.findByEmail(onlyAdmin.email())).thenReturn(Optional.of(onlyAdmin));
        when(store.countActiveNonDemoAdminsExcluding(onlyAdmin.email())).thenReturn(0L);
        when(store.enabled()).thenReturn(true);
        AuthService service = service(store);

        LastAdminProtectionException ex = assertThrows(LastAdminProtectionException.class,
            () -> service.activateProfessional(adminClaims, onlyAdmin.email(), false));
        assertEquals("LAST_ADMIN_PROTECTION", ex.code());
        assertEquals(409, ex.status().value());
    }

    @Test
    void withTwoAdminsOneCanBeDeactivated() {
        PostgresAuthStoreService store = mock(PostgresAuthStoreService.class);
        DoctorAccount first = admin("first-admin@pfi.local");
        when(store.findByEmail(first.email())).thenReturn(Optional.of(first));
        when(store.countActiveNonDemoAdminsExcluding(first.email())).thenReturn(1L);
        when(store.enabled()).thenReturn(true);
        AuthService service = service(store);

        var response = service.activateProfessional(adminClaims, first.email(), false);

        assertEquals("deactivated", response.status());
    }

    @Test
    void demoAdminAccountDoesNotCountTowardsTheProtection() {
        PostgresAuthStoreService store = mock(PostgresAuthStoreService.class);
        DoctorAccount realAdmin = admin("real-admin@pfi.local");
        when(store.findByEmail(realAdmin.email())).thenReturn(Optional.of(realAdmin));
        // The store itself excludes the demo account from this count (see
        // PostgresAuthStoreService.countActiveNonDemoAdminsExcluding) — even though two
        // ADMIN rows exist in Postgres, realAdmin is the only NON-DEMO one.
        when(store.countActiveNonDemoAdminsExcluding(realAdmin.email())).thenReturn(0L);
        when(store.enabled()).thenReturn(true);
        AuthService service = service(store);

        assertThrows(LastAdminProtectionException.class,
            () -> service.activateProfessional(adminClaims, realAdmin.email(), false));
    }

    @Test
    void aNormalProfessionalDeactivationIsNeverAffectedByThisRule() {
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
    void countQueryFailureBlocksTheOperationRatherThanAssumingOtherAdminsExist() {
        PostgresAuthStoreService store = mock(PostgresAuthStoreService.class);
        DoctorAccount onlyAdmin = admin("only-admin-2@pfi.local");
        when(store.findByEmail(onlyAdmin.email())).thenReturn(Optional.of(onlyAdmin));
        when(store.enabled()).thenReturn(true);
        when(store.countActiveNonDemoAdminsExcluding(onlyAdmin.email())).thenThrow(new IllegalStateException("connection failed"));
        AuthService service = service(store);

        assertThrows(LastAdminProtectionException.class,
            () -> service.activateProfessional(adminClaims, onlyAdmin.email(), false));
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
