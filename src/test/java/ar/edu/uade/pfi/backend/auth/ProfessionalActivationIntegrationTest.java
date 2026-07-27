package ar.edu.uade.pfi.backend.auth;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import ar.edu.uade.pfi.backend.auth.dto.AuthDtos.ProfessionalActivationResponse;
import ar.edu.uade.pfi.backend.auth.dto.AuthDtos.TokenResponse;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.web.server.ResponseStatusException;

/**
 * Business-logic coverage for AuthService.activateProfessional — the AuthService/
 * PostgresAuthStoreService collaboration, independent of HTTP wiring (that's covered by
 * ProfessionalActivationControllerTest).
 */
class ProfessionalActivationIntegrationTest {
    private final PasswordHasher passwordHasher = new PasswordHasher();
    private final TokenService tokenService = new TokenService(new ObjectMapper(), "test-only-secret-at-least-32-bytes-long!!", 3600);
    private final TokenService.Claims adminClaims = new TokenService.Claims("admin-1", "admin@pfi.local", "Admin", List.of("ADMIN"));

    @Test
    void activatingSetsVerifiedApprovedAndExactRoles() {
        PostgresAuthStoreService store = mock(PostgresAuthStoreService.class);
        DoctorAccount pending = pendingAccount();
        when(store.findByEmail(pending.email())).thenReturn(Optional.of(pending));
        AuthService service = service(store);

        ProfessionalActivationResponse response = service.activateProfessional(adminClaims, pending.email(), true);

        assertEquals("activated", response.status());
        assertTrue(response.verified());
        assertTrue(response.approved());
        assertEquals(List.of("DOCTOR", "REVIEWER"), response.roles());
        verify(store).updateProfessionalActivation(pending.email(), true, true, List.of("DOCTOR", "REVIEWER"));
    }

    @Test
    void activatingDoesNotChangePasswordHash() {
        PostgresAuthStoreService store = mock(PostgresAuthStoreService.class);
        DoctorAccount pending = pendingAccount();
        String originalHash = pending.passwordHash();
        when(store.findByEmail(pending.email())).thenReturn(Optional.of(pending));
        AuthService service = service(store);

        service.activateProfessional(adminClaims, pending.email(), true);

        assertEquals(originalHash, pending.passwordHash());
    }

    @Test
    void loginWorksAfterActivationWithOriginalPassword() {
        PostgresAuthStoreService store = mock(PostgresAuthStoreService.class);
        DoctorAccount pending = pendingAccount();
        when(store.findByEmail(pending.email())).thenReturn(Optional.of(pending));
        AuthService service = service(store);

        service.activateProfessional(adminClaims, pending.email(), true);
        Object result = service.login(pending.email(), "OriginalPass123!");

        assertTrue(result instanceof TokenResponse);
        assertTrue(((TokenResponse) result).user().roles().contains("DOCTOR"));
    }

    @Test
    void activationItselfNeverIssuesAToken() {
        PostgresAuthStoreService store = mock(PostgresAuthStoreService.class);
        DoctorAccount pending = pendingAccount();
        when(store.findByEmail(pending.email())).thenReturn(Optional.of(pending));
        AuthService service = service(store);

        service.activateProfessional(adminClaims, pending.email(), true);

        verify(store, never()).saveRefreshToken(any(), any(), any());
    }

    @Test
    void deactivatingSetsPendingApprovalAndRevokesRefreshTokens() {
        PostgresAuthStoreService store = mock(PostgresAuthStoreService.class);
        DoctorAccount active = activeAccount();
        when(store.findByEmail(active.email())).thenReturn(Optional.of(active));
        AuthService service = service(store);

        ProfessionalActivationResponse response = service.activateProfessional(adminClaims, active.email(), false);

        assertEquals("deactivated", response.status());
        assertEquals(List.of("PENDING_APPROVAL"), response.roles());
        assertFalse(response.approved());
        verify(store).updateProfessionalActivation(active.email(), true, false, List.of("PENDING_APPROVAL"));
        verify(store).revokeRefreshTokensForEmail(active.email());
    }

    @Test
    void anOldPendingChallengeIsInvalidatedByActivationAndCannotBeUsedAfterwards() {
        PostgresAuthStoreService store = mock(PostgresAuthStoreService.class);
        AuthService service = service(store);
        var pendingResponse = service.register(new ar.edu.uade.pfi.backend.auth.dto.AuthDtos.RegisterRequest(
            "Dr New", "challenge.test@hospital.example", "OriginalPass123!", "MN-1", "Spine", "Hospital"
        ));

        service.activateProfessional(adminClaims, "challenge.test@hospital.example", true);

        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
            () -> service.verify(pendingResponse.challengeId(), "000000"));
        assertEquals(401, ex.getStatusCode().value());
    }

    @Test
    void deactivatedAccountLoginOnlyEverGetsPendingApprovalRole() {
        PostgresAuthStoreService store = mock(PostgresAuthStoreService.class);
        DoctorAccount active = activeAccount();
        when(store.findByEmail(active.email())).thenReturn(Optional.of(active));
        AuthService service = service(store);

        service.activateProfessional(adminClaims, active.email(), false);
        Object result = service.login(active.email(), "OriginalPass123!");

        assertTrue(result instanceof TokenResponse);
        List<String> roles = ((TokenResponse) result).user().roles();
        assertEquals(List.of("PENDING_APPROVAL"), roles);
        assertFalse(roles.contains("DOCTOR"));
    }

    @Test
    void demoAccountActivationIsForbidden() {
        PostgresAuthStoreService store = mock(PostgresAuthStoreService.class);
        AuthService service = service(store);

        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
            () -> service.activateProfessional(adminClaims, AuthService.DEMO_ACCOUNT_EMAIL, true));
        assertEquals(403, ex.getStatusCode().value());
    }

    @Test
    void unknownAccountReturns404() {
        PostgresAuthStoreService store = mock(PostgresAuthStoreService.class);
        when(store.findByEmail("nobody@hospital.example")).thenReturn(Optional.empty());
        AuthService service = service(store);

        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
            () -> service.activateProfessional(adminClaims, "nobody@hospital.example", true));
        assertEquals(404, ex.getStatusCode().value());
    }

    @Test
    void nonAdminCallerIsForbidden() {
        PostgresAuthStoreService store = mock(PostgresAuthStoreService.class);
        DoctorAccount pending = pendingAccount();
        when(store.findByEmail(pending.email())).thenReturn(Optional.of(pending));
        AuthService service = service(store);
        TokenService.Claims doctorClaims = new TokenService.Claims("doc-1", "doc@hospital.example", "Doc", List.of("DOCTOR", "REVIEWER"));

        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
            () -> service.activateProfessional(doctorClaims, pending.email(), true));
        assertEquals(403, ex.getStatusCode().value());
    }

    private DoctorAccount pendingAccount() {
        return new DoctorAccount(
            "pending-1", "Dr. New Professional", "new.professional@hospital.example",
            passwordHasher.hash("OriginalPass123!"), "", "", "",
            List.of("PENDING_APPROVAL"), Instant.now(), false, false, false, false
        );
    }

    private DoctorAccount activeAccount() {
        return new DoctorAccount(
            "active-1", "Dr. Active Professional", "active.professional@hospital.example",
            passwordHasher.hash("OriginalPass123!"), "MN-1", "Spine", "Hospital",
            List.of("DOCTOR", "REVIEWER"), Instant.now(), true, true, false, true
        );
    }

    private AuthService service(PostgresAuthStoreService store) {
        return new AuthService(passwordHasher, tokenService, store, false, 604800, new MockEnvironment(), false, null);
    }
}
