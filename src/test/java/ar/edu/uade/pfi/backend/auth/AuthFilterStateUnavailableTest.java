package ar.edu.uade.pfi.backend.auth;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import ar.edu.uade.pfi.backend.controller.StudyController;
import ar.edu.uade.pfi.backend.service.AuditService;
import ar.edu.uade.pfi.backend.service.ProfessionalAccessAuditService;
import ar.edu.uade.pfi.backend.service.StudyRunService;
import ar.edu.uade.pfi.backend.service.StudyWorklistService;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/**
 * P10-A.2.1 §3: when Postgres cannot confirm a production session's current state, a
 * protected request must fail closed with 503 AUTH_STATE_UNAVAILABLE — it must never
 * fall through to trusting the JWT's own claims in production.
 */
class AuthFilterStateUnavailableTest {
    private static final String SECRET = "state-unavailable-test-secret-32-bytes-long!!";

    @Test
    void productionWithUnresolvableAccountStateReturns503() throws Exception {
        TokenService tokenService = new TokenService(new ObjectMapper(), SECRET, 3600);
        PostgresAuthStoreService store = mock(PostgresAuthStoreService.class);
        when(store.enabled()).thenReturn(false); // Postgres disabled in a "production" environment is itself a fail-closed condition.
        MockEnvironment production = new MockEnvironment();
        production.setActiveProfiles("production");
        AuthAccountStateService accountStateService = new AuthAccountStateService(store, production);
        AuthFilter authFilter = new AuthFilter(tokenService, accountStateService, true, false, production);

        StudyController studyController = new StudyController(
            mock(StudyWorklistService.class), mock(StudyRunService.class), mock(ProfessionalAccessAuditService.class),
            new RoleAuthorizationService(new AuditService(new ar.edu.uade.pfi.backend.repository.InMemoryStudyRepository()))
        );

        MockMvc mockMvc = MockMvcBuilders.standaloneSetup(studyController).addFilter(authFilter).build();

        String token = tokenService.issueAccessToken(new DoctorAccount(
            "user-1", "Dr Doc", "doc@hospital.example", "hash", "", "", "", List.of("DOCTOR", "REVIEWER"),
            Instant.now(), true, true, false, true
        ));

        mockMvc.perform(get("/api/studies").header("Authorization", "Bearer " + token))
            .andExpect(status().isServiceUnavailable())
            .andExpect(jsonPath("$.code").value("AUTH_STATE_UNAVAILABLE"));
    }
}
