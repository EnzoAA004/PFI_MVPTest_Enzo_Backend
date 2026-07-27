package ar.edu.uade.pfi.backend.auth;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import ar.edu.uade.pfi.backend.auth.dto.AuthDtos.ProfessionalActivationResponse;
import ar.edu.uade.pfi.backend.service.AuditService;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.FilterType;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

/**
 * P10-A.2.1 §10/§11: proves the unknown-field rejection on
 * ProfessionalActivationRequest holds against the REAL Spring Boot-managed Jackson
 * ObjectMapper (via @WebMvcTest, not a hand-built `new ObjectMapper()` in a standalone
 * MockMvc as P10-A.2's test did). AuthFilter is excluded from this slice — it needs
 * Postgres/token infrastructure that is irrelevant to "does the DTO reject an unknown
 * field", which is already covered end-to-end (through the real filter) by
 * ProfessionalActivationControllerTest and AccountStateImmediateInvalidationIntegrationTest.
 */
@WebMvcTest(
    controllers = AuthController.class,
    excludeFilters = @ComponentScan.Filter(type = FilterType.ASSIGNABLE_TYPE, classes = AuthFilter.class)
)
class ProfessionalActivationRealObjectMapperTest {
    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private AuthService authService;

    @MockBean
    private AuditService auditService;

    @MockBean
    private RoleAuthorizationService authorizationService;

    @Test
    void exactPayloadIsAccepted() throws Exception {
        when(authService.activateProfessional(any(), anyString(), anyBoolean()))
            .thenReturn(new ProfessionalActivationResponse("doc@example.com", "activated", true, true, List.of("DOCTOR", "REVIEWER")));

        mockMvc.perform(patch("/api/auth/admin/professionals/activation")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"doc@example.com\",\"activated\":true}"))
            .andExpect(status().isOk());
    }

    @Test
    void rolesFieldIsRejectedWith400ByTheRealSpringBootObjectMapper() throws Exception {
        assertRejected("{\"email\":\"doc@example.com\",\"activated\":true,\"roles\":[\"ADMIN\"]}");
    }

    @Test
    void adminFlagIsRejected() throws Exception {
        assertRejected("{\"email\":\"doc@example.com\",\"activated\":true,\"admin\":true}");
    }

    @Test
    void passwordFieldIsRejected() throws Exception {
        assertRejected("{\"email\":\"doc@example.com\",\"activated\":true,\"password\":\"whatever\"}");
    }

    @Test
    void authoritiesFieldIsRejected() throws Exception {
        assertRejected("{\"email\":\"doc@example.com\",\"activated\":true,\"authorities\":[\"ADMIN\"]}");
    }

    @Test
    void permissionsFieldIsRejected() throws Exception {
        assertRejected("{\"email\":\"doc@example.com\",\"activated\":true,\"permissions\":[\"ALL\"]}");
    }

    @Test
    void verifiedFieldIsRejected() throws Exception {
        assertRejected("{\"email\":\"doc@example.com\",\"activated\":true,\"verified\":true}");
    }

    @Test
    void approvedFieldIsRejected() throws Exception {
        assertRejected("{\"email\":\"doc@example.com\",\"activated\":true,\"approved\":true}");
    }

    private void assertRejected(String payload) throws Exception {
        mockMvc.perform(patch("/api/auth/admin/professionals/activation")
                .contentType(MediaType.APPLICATION_JSON)
                .content(payload))
            .andExpect(status().isBadRequest());
        verify(authService, never()).activateProfessional(any(), anyString(), anyBoolean());
    }
}
