package ar.edu.uade.pfi.backend.controller;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import ar.edu.uade.pfi.backend.auth.AuthFilter;
import ar.edu.uade.pfi.backend.auth.DoctorAccount;
import ar.edu.uade.pfi.backend.auth.RoleAuthorizationService;
import ar.edu.uade.pfi.backend.auth.TokenService;
import ar.edu.uade.pfi.backend.client.AiServiceClient;
import ar.edu.uade.pfi.backend.config.ApiExceptionHandler;
import ar.edu.uade.pfi.backend.repository.InMemoryStudyRepository;
import ar.edu.uade.pfi.backend.service.AuditService;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/**
 * P10-B.2: GET /api/ai/models/runtime previously had no authorization check beyond
 * AuthFilter's blanket "any authenticated professional" gate — this is the HTTP-layer
 * matrix proving anonymous->401, DOCTOR/REVIEWER->403, ADMIN->200 now hold end to end
 * through the real AuthFilter + RoleAuthorizationService, not just at the unit level
 * covered by AiModelRuntimeControllerTest.
 */
class AiModelRuntimeControllerAuthorizationTest {
    private static final String SECRET = "ai-runtime-authz-test-secret-32-bytes!!";
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final TokenService tokenService = new TokenService(objectMapper, SECRET, 3600);
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        AiServiceClient client = mock(AiServiceClient.class);
        when(client.getModelRuntime()).thenReturn(Map.of("status", "pytorch_runtime_ready", "device", "cpu"));
        AuditService auditService = new AuditService(new InMemoryStudyRepository());
        RoleAuthorizationService authorizationService = new RoleAuthorizationService(auditService);
        AiModelRuntimeController controller = new AiModelRuntimeController(client, authorizationService);

        mockMvc = MockMvcBuilders
            .standaloneSetup(controller)
            .addFilter(new AuthFilter(
                tokenService,
                new ar.edu.uade.pfi.backend.auth.AuthAccountStateService(mock(ar.edu.uade.pfi.backend.auth.PostgresAuthStoreService.class), new MockEnvironment()),
                true, false, new MockEnvironment()))
            .setControllerAdvice(new ApiExceptionHandler(auditService))
            .build();
    }

    @Test
    void anonymousIsUnauthorized() throws Exception {
        mockMvc.perform(get("/api/ai/models/runtime"))
            .andExpect(status().isUnauthorized())
            .andExpect(jsonPath("$.code").value("AUTHENTICATION_REQUIRED"));
    }

    @Test
    void pendingApprovalIsForbidden() throws Exception {
        mockMvc.perform(get("/api/ai/models/runtime").header("Authorization", bearer(List.of("PENDING_APPROVAL"))))
            .andExpect(status().isForbidden());
    }

    @Test
    void doctorIsForbidden() throws Exception {
        mockMvc.perform(get("/api/ai/models/runtime").header("Authorization", bearer(List.of("DOCTOR"))))
            .andExpect(status().isForbidden())
            .andExpect(jsonPath("$.code").value("ACCESS_DENIED"));
    }

    @Test
    void reviewerIsForbidden() throws Exception {
        mockMvc.perform(get("/api/ai/models/runtime").header("Authorization", bearer(List.of("REVIEWER"))))
            .andExpect(status().isForbidden());
    }

    @Test
    void adminIsAllowed() throws Exception {
        mockMvc.perform(get("/api/ai/models/runtime").header("Authorization", bearer(List.of("ADMIN"))))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.status").value("pytorch_runtime_ready"));
    }

    private String bearer(List<String> roles) {
        return "Bearer " + tokenService.issueAccessToken(
            new DoctorAccount("user-1", "Test User", "user@example.com", "hash", "", "", "", roles,
                Instant.now(), true, true, false, true)
        );
    }
}
