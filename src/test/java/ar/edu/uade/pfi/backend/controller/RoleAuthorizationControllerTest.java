package ar.edu.uade.pfi.backend.controller;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import ar.edu.uade.pfi.backend.auth.AuthFilter;
import ar.edu.uade.pfi.backend.auth.RoleAuthorizationService;
import ar.edu.uade.pfi.backend.auth.TokenService;
import ar.edu.uade.pfi.backend.client.AiServiceOperations;
import ar.edu.uade.pfi.backend.config.ApiExceptionHandler;
import ar.edu.uade.pfi.backend.repository.InMemoryStudyRepository;
import ar.edu.uade.pfi.backend.service.AuditService;
import ar.edu.uade.pfi.backend.service.SystemDiagnosticsService;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class RoleAuthorizationControllerTest {
    private AuditService auditService;
    private RoleAuthorizationService authorizationService;

    @BeforeEach
    void setUp() {
        auditService = new AuditService(new InMemoryStudyRepository());
        authorizationService = new RoleAuthorizationService(auditService);
    }

    @Test
    void adminCanSyncModels() throws Exception {
        AiServiceOperations ai = mock(AiServiceOperations.class);
        when(ai.syncModels(true)).thenReturn(new LinkedHashMap<>(Map.of("status", "ok")));
        MockMvc mockMvc = MockMvcBuilders
            .standaloneSetup(new AiModelSyncController(ai, authorizationService))
            .setControllerAdvice(new ApiExceptionHandler(auditService))
            .build();

        mockMvc.perform(post("/api/ai/models/sync?force=true").with(admin()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.status").value("ok"))
            .andExpect(jsonPath("$.proxiedByBackend").value(true));

        verify(ai).syncModels(true);
    }

    @Test
    void adminCanReadDiagnostics() throws Exception {
        SystemDiagnosticsService diagnosticsService = mock(SystemDiagnosticsService.class);
        when(diagnosticsService.diagnostics()).thenReturn(Map.of("status", "ok"));
        MockMvc mockMvc = MockMvcBuilders
            .standaloneSetup(new SystemController(diagnosticsService, authorizationService))
            .setControllerAdvice(new ApiExceptionHandler(auditService))
            .build();

        mockMvc.perform(get("/api/system/diagnostics").with(admin()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.status").value("ok"));
    }

    @Test
    void insufficientRoleGetsForbiddenForAdminEndpoints() throws Exception {
        AiServiceOperations ai = mock(AiServiceOperations.class);
        MockMvc mockMvc = MockMvcBuilders
            .standaloneSetup(new AiModelSyncController(ai, authorizationService))
            .setControllerAdvice(new ApiExceptionHandler(auditService))
            .build();

        mockMvc.perform(post("/api/ai/models/sync").with(reviewer()))
            .andExpect(status().isForbidden())
            .andExpect(jsonPath("$.message").value("Rol insuficiente"));
    }

    private static RequestPostProcessor admin() {
        return claims("admin-id", List.of("ADMIN"));
    }

    private static RequestPostProcessor reviewer() {
        return claims("reviewer-id", List.of("REVIEWER"));
    }

    private static RequestPostProcessor claims(String subject, List<String> roles) {
        return request -> {
            request.setAttribute(AuthFilter.AUTH_CLAIMS_ATTRIBUTE, new TokenService.Claims(subject, "", "", roles));
            return request;
        };
    }
}
