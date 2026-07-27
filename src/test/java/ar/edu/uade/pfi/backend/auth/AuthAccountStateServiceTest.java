package ar.edu.uade.pfi.backend.auth;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

class AuthAccountStateServiceTest {
    private final TokenService.Claims tokenClaims = new TokenService.Claims("user-1", "doc@hospital.example", "Dr X", List.of("DOCTOR", "REVIEWER"));

    @Test
    void nonProductionTrustsTheJwtClaimsDirectlyWithoutQueryingPostgres() {
        PostgresAuthStoreService store = mock(PostgresAuthStoreService.class);
        AuthAccountStateService service = new AuthAccountStateService(store, new MockEnvironment());

        var resolution = service.resolve(tokenClaims);

        assertEquals(AuthAccountStateService.Status.OK, resolution.status());
        assertEquals(tokenClaims, resolution.claims());
        org.mockito.Mockito.verifyNoInteractions(store);
    }

    @Test
    void productionWithPostgresDisabledIsStateUnavailable() {
        PostgresAuthStoreService store = mock(PostgresAuthStoreService.class);
        when(store.enabled()).thenReturn(false);
        AuthAccountStateService service = new AuthAccountStateService(store, production());

        var resolution = service.resolve(tokenClaims);

        assertEquals(AuthAccountStateService.Status.STATE_UNAVAILABLE, resolution.status());
    }

    @Test
    void productionWithQueryFailureIsStateUnavailable() {
        PostgresAuthStoreService store = mock(PostgresAuthStoreService.class);
        when(store.enabled()).thenReturn(true);
        when(store.findByEmailForAuthorization("doc@hospital.example")).thenThrow(new IllegalStateException("connection failed"));
        AuthAccountStateService service = new AuthAccountStateService(store, production());

        var resolution = service.resolve(tokenClaims);

        assertEquals(AuthAccountStateService.Status.STATE_UNAVAILABLE, resolution.status());
    }

    @Test
    void productionWithUnknownAccountIsAccountNotFound() {
        PostgresAuthStoreService store = mock(PostgresAuthStoreService.class);
        when(store.enabled()).thenReturn(true);
        when(store.findByEmailForAuthorization("doc@hospital.example")).thenReturn(Optional.empty());
        AuthAccountStateService service = new AuthAccountStateService(store, production());

        var resolution = service.resolve(tokenClaims);

        assertEquals(AuthAccountStateService.Status.ACCOUNT_NOT_FOUND, resolution.status());
    }

    @Test
    void productionWithMismatchedSubjectIsAccountNotFound() {
        PostgresAuthStoreService store = mock(PostgresAuthStoreService.class);
        when(store.enabled()).thenReturn(true);
        DoctorAccount persisted = account("different-id", "doc@hospital.example", List.of("DOCTOR", "REVIEWER"));
        when(store.findByEmailForAuthorization("doc@hospital.example")).thenReturn(Optional.of(persisted));
        AuthAccountStateService service = new AuthAccountStateService(store, production());

        var resolution = service.resolve(tokenClaims);

        assertEquals(AuthAccountStateService.Status.ACCOUNT_NOT_FOUND, resolution.status());
    }

    @Test
    void productionWithDemoAccountIsDemoBlocked() {
        PostgresAuthStoreService store = mock(PostgresAuthStoreService.class);
        when(store.enabled()).thenReturn(true);
        DoctorAccount demo = account("user-1", AuthService.DEMO_ACCOUNT_EMAIL, List.of("ADMIN", "DOCTOR", "REVIEWER"));
        TokenService.Claims demoTokenClaims = new TokenService.Claims("user-1", AuthService.DEMO_ACCOUNT_EMAIL, "Demo", List.of("ADMIN"));
        when(store.findByEmailForAuthorization(AuthService.DEMO_ACCOUNT_EMAIL)).thenReturn(Optional.of(demo));
        AuthAccountStateService service = new AuthAccountStateService(store, production());

        var resolution = service.resolve(demoTokenClaims);

        assertEquals(AuthAccountStateService.Status.DEMO_BLOCKED, resolution.status());
    }

    @Test
    void productionEffectiveClaimsComeFromPersistedAccountNotTheToken() {
        PostgresAuthStoreService store = mock(PostgresAuthStoreService.class);
        when(store.enabled()).thenReturn(true);
        // Token claims say DOCTOR,REVIEWER, but the account has since been deactivated.
        DoctorAccount deactivated = account("user-1", "doc@hospital.example", List.of("PENDING_APPROVAL"));
        when(store.findByEmailForAuthorization("doc@hospital.example")).thenReturn(Optional.of(deactivated));
        AuthAccountStateService service = new AuthAccountStateService(store, production());

        var resolution = service.resolve(tokenClaims);

        assertEquals(AuthAccountStateService.Status.OK, resolution.status());
        assertEquals(List.of("PENDING_APPROVAL"), resolution.claims().roles());
    }

    /**
     * P10-A.2.2 §2/§3: approved/verified are authoritative, not just roles — a row left
     * with stale DOCTOR/REVIEWER roles but verified=false must never yield effective
     * claims carrying those roles.
     */
    @Test
    void unverifiedAccountWithStaleProfessionalRolesGetsPendingApprovalEffectiveRoles() {
        PostgresAuthStoreService store = mock(PostgresAuthStoreService.class);
        when(store.enabled()).thenReturn(true);
        DoctorAccount unverified = accountWithState("user-1", "doc@hospital.example", List.of("DOCTOR", "REVIEWER"), false, true);
        when(store.findByEmailForAuthorization("doc@hospital.example")).thenReturn(Optional.of(unverified));
        AuthAccountStateService service = new AuthAccountStateService(store, production());

        var resolution = service.resolve(tokenClaims);

        assertEquals(AuthAccountStateService.Status.OK, resolution.status());
        assertEquals(List.of("PENDING_APPROVAL"), resolution.claims().roles());
        assertEquals(false, resolution.verified());
        assertEquals(true, resolution.approved());
    }

    @Test
    void unapprovedAccountWithStaleProfessionalRolesGetsPendingApprovalEffectiveRoles() {
        PostgresAuthStoreService store = mock(PostgresAuthStoreService.class);
        when(store.enabled()).thenReturn(true);
        DoctorAccount unapproved = accountWithState("user-1", "doc@hospital.example", List.of("DOCTOR", "REVIEWER"), true, false);
        when(store.findByEmailForAuthorization("doc@hospital.example")).thenReturn(Optional.of(unapproved));
        AuthAccountStateService service = new AuthAccountStateService(store, production());

        var resolution = service.resolve(tokenClaims);

        assertEquals(AuthAccountStateService.Status.OK, resolution.status());
        assertEquals(List.of("PENDING_APPROVAL"), resolution.claims().roles());
        assertEquals(true, resolution.verified());
        assertEquals(false, resolution.approved());
    }

    @Test
    void unapprovedAdminAccountAlsoGetsPendingApprovalEffectiveRoles() {
        PostgresAuthStoreService store = mock(PostgresAuthStoreService.class);
        when(store.enabled()).thenReturn(true);
        DoctorAccount unapprovedAdmin = accountWithState("user-1", "doc@hospital.example", List.of("ADMIN", "DOCTOR", "REVIEWER"), true, false);
        TokenService.Claims adminTokenClaims = new TokenService.Claims("user-1", "doc@hospital.example", "Dr X", List.of("ADMIN", "DOCTOR", "REVIEWER"));
        when(store.findByEmailForAuthorization("doc@hospital.example")).thenReturn(Optional.of(unapprovedAdmin));
        AuthAccountStateService service = new AuthAccountStateService(store, production());

        var resolution = service.resolve(adminTokenClaims);

        assertEquals(List.of("PENDING_APPROVAL"), resolution.claims().roles());
    }

    @Test
    void verifiedAndApprovedAccountKeepsItsRealRoles() {
        PostgresAuthStoreService store = mock(PostgresAuthStoreService.class);
        when(store.enabled()).thenReturn(true);
        DoctorAccount active = account("user-1", "doc@hospital.example", List.of("DOCTOR", "REVIEWER"));
        when(store.findByEmailForAuthorization("doc@hospital.example")).thenReturn(Optional.of(active));
        AuthAccountStateService service = new AuthAccountStateService(store, production());

        var resolution = service.resolve(tokenClaims);

        assertEquals(List.of("DOCTOR", "REVIEWER"), resolution.claims().roles());
        assertEquals(true, resolution.verified());
        assertEquals(true, resolution.approved());
    }

    private DoctorAccount account(String id, String email, List<String> roles) {
        return accountWithState(id, email, roles, true, true);
    }

    private DoctorAccount accountWithState(String id, String email, List<String> roles, boolean verified, boolean approved) {
        return new DoctorAccount(id, "Dr X", email, "hash", "", "", "", roles, Instant.now(), verified, approved, false, true);
    }

    private MockEnvironment production() {
        MockEnvironment env = new MockEnvironment();
        env.setActiveProfiles("production");
        return env;
    }
}
