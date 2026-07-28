package ar.edu.uade.pfi.backend.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import ar.edu.uade.pfi.backend.auth.dto.AuthDtos.TokenResponse;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

/**
 * P10-B.2 §3: tokens must always reflect the account's *current, effective* state — a
 * deactivated account never mints a token with real professional roles, reactivation
 * mints a token with exactly the roles that were preserved (never more, never fewer),
 * and a deactivated account can no longer refresh at all.
 */
class TokenAfterStateChangeTest {
    private final PasswordHasher passwordHasher = new PasswordHasher();
    private final TokenService tokenService = new TokenService(new ObjectMapper(), "token-state-change-test-secret-32b!!", 3600);
    private final TokenService.Claims adminClaims = new TokenService.Claims("admin-1", "admin@pfi.local", "Admin", List.of("ADMIN"));

    @Test
    void reactivatedAccountLoginTokenCarriesExactlyItsOriginalRoles() {
        PostgresAuthStoreService store = mock(PostgresAuthStoreService.class);
        DoctorAccount account = accountWithRoles(List.of("DOCTOR", "REVIEWER"));
        when(store.findByEmail(account.email())).thenReturn(Optional.of(account));
        AuthService service = service(store);

        service.activateProfessional(adminClaims, account.email(), false);
        service.activateProfessional(adminClaims, account.email(), true);
        Object result = service.login(account.email(), "OriginalPass123!");

        assertTrue(result instanceof TokenResponse);
        List<String> roles = ((TokenResponse) result).user().roles();
        assertThat(roles).containsExactlyInAnyOrder("DOCTOR", "REVIEWER");

        TokenService.Claims decoded = tokenService.verify(((TokenResponse) result).accessToken());
        assertThat(decoded.roles()).containsExactlyInAnyOrder("DOCTOR", "REVIEWER");
    }

    @Test
    void reactivationNeverGrantsAdminEvenIfOriginalRolesAreASingleRestrictedRole() {
        PostgresAuthStoreService store = mock(PostgresAuthStoreService.class);
        DoctorAccount account = accountWithRoles(List.of("REVIEWER"));
        when(store.findByEmail(account.email())).thenReturn(Optional.of(account));
        AuthService service = service(store);

        service.activateProfessional(adminClaims, account.email(), false);
        service.activateProfessional(adminClaims, account.email(), true);
        Object result = service.login(account.email(), "OriginalPass123!");

        List<String> roles = ((TokenResponse) result).user().roles();
        assertThat(roles).containsExactlyInAnyOrder("REVIEWER");
        assertThat(roles).doesNotContain("ADMIN", "DOCTOR");
    }

    @Test
    void deactivatedAccountCannotRefreshItsSession() {
        PostgresAuthStoreService store = mock(PostgresAuthStoreService.class);
        DoctorAccount account = accountWithRoles(List.of("DOCTOR", "REVIEWER"));
        when(store.findByEmail(account.email())).thenReturn(Optional.of(account));
        AuthService service = service(store);
        Object loginResult = service.login(account.email(), "OriginalPass123!");
        String refreshToken = ((TokenResponse) loginResult).refreshToken();

        service.activateProfessional(adminClaims, account.email(), false);

        ResponseStatusException ex = assertThrows(ResponseStatusException.class, () -> service.refresh(refreshToken));
        assertEquals(401, ex.getStatusCode().value());
    }

    @Test
    void deactivatedAccountLoginTokenNeverCarriesItsPreservedProfessionalRoles() {
        PostgresAuthStoreService store = mock(PostgresAuthStoreService.class);
        DoctorAccount account = accountWithRoles(List.of("DOCTOR", "REVIEWER"));
        when(store.findByEmail(account.email())).thenReturn(Optional.of(account));
        AuthService service = service(store);

        service.activateProfessional(adminClaims, account.email(), false);
        // Roles are preserved in storage (the whole point of this hotfix) ...
        assertThat(account.roles()).containsExactlyInAnyOrder("DOCTOR", "REVIEWER");

        // ... but a token minted for this now-deactivated account must never carry them.
        Object result = service.login(account.email(), "OriginalPass123!");
        TokenService.Claims decoded = tokenService.verify(((TokenResponse) result).accessToken());
        assertThat(decoded.roles()).containsExactlyInAnyOrder("PENDING_APPROVAL");
    }

    private DoctorAccount accountWithRoles(List<String> roles) {
        return new DoctorAccount(
            UUID.randomUUID().toString(), "Dr. Test Professional", "professional." + UUID.randomUUID() + "@hospital.example",
            passwordHasher.hash("OriginalPass123!"), "MN-1", "Spine", "Hospital",
            roles, Instant.now(), true, true, false, true
        );
    }

    private AuthService service(PostgresAuthStoreService store) {
        return new AuthService(passwordHasher, tokenService, store, false, 604800, new org.springframework.mock.env.MockEnvironment(), false, null);
    }
}
