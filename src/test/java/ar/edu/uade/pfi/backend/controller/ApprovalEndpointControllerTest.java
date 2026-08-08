package ar.edu.uade.pfi.backend.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import ar.edu.uade.pfi.backend.auth.AuthController;
import ar.edu.uade.pfi.backend.auth.AuthFilter;
import ar.edu.uade.pfi.backend.auth.AuthService;
import ar.edu.uade.pfi.backend.auth.RoleAuthorizationService;
import ar.edu.uade.pfi.backend.auth.TokenService;
import ar.edu.uade.pfi.backend.auth.dto.AuthDtos.UserResponse;
import ar.edu.uade.pfi.backend.config.ApiExceptionHandler;
import ar.edu.uade.pfi.backend.repository.InMemoryStudyRepository;
import ar.edu.uade.pfi.backend.service.AuditService;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/**
 * P10-A.2.1 §6/§12: the legacy PATCH /api/auth/admin/professionals/approval endpoint (kept for
 * frontend compatibility) must now enforce exactly the same authorization matrix as /activation —
 * it no longer bypasses RoleAuthorizationService, last-ADMIN protection, or demo blocking. Full
 * behavioral coverage (last-ADMIN 409, session revocation, immediate token invalidation) lives in
 * AccountStateImmediateInvalidationIntegrationTest; this file focuses on the HTTP-layer
 * authorization matrix for /approval specifically.
 */
class ApprovalEndpointControllerTest {
  private static final String SECRET = "approval-test-secret-at-least-32-bytes-long!!";
  private final ObjectMapper objectMapper = new ObjectMapper();
  private final TokenService tokenService = new TokenService(objectMapper, SECRET, 3600);
  private AuthService authService;
  private MockMvc mockMvc;

  @BeforeEach
  void setUp() {
    authService = mock(AuthService.class);
    AuditService auditService = new AuditService(new InMemoryStudyRepository());
    RoleAuthorizationService authorizationService = new RoleAuthorizationService(auditService);
    AuthController controller = new AuthController(authService, auditService, authorizationService);

    mockMvc =
        MockMvcBuilders.standaloneSetup(controller)
            .addFilter(
                new AuthFilter(
                    tokenService,
                    new ar.edu.uade.pfi.backend.auth.AuthAccountStateService(
                        mock(ar.edu.uade.pfi.backend.auth.PostgresAuthStoreService.class),
                        new MockEnvironment()),
                    true,
                    false,
                    new MockEnvironment()))
            .setControllerAdvice(new ApiExceptionHandler(auditService))
            .build();
  }

  @Test
  void anonymousIsUnauthorized() throws Exception {
    mockMvc
        .perform(
            patch("/api/auth/admin/professionals/approval")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"doc@hospital.example\",\"approved\":true}"))
        .andExpect(status().isUnauthorized())
        .andExpect(jsonPath("$.code").value("AUTHENTICATION_REQUIRED"));
    verify(authService, never()).approveProfessional(any(), anyString(), anyBoolean());
  }

  @Test
  void pendingApprovalIsForbidden() throws Exception {
    mockMvc
        .perform(
            patch("/api/auth/admin/professionals/approval")
                .header("Authorization", bearer(List.of("PENDING_APPROVAL")))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"doc@hospital.example\",\"approved\":true}"))
        .andExpect(status().isForbidden());
  }

  @Test
  void doctorIsForbidden() throws Exception {
    mockMvc
        .perform(
            patch("/api/auth/admin/professionals/approval")
                .header("Authorization", bearer(List.of("DOCTOR")))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"doc@hospital.example\",\"approved\":true}"))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.code").value("ACCESS_DENIED"));
    verify(authService, never()).approveProfessional(any(), anyString(), anyBoolean());
  }

  @Test
  void reviewerIsForbidden() throws Exception {
    mockMvc
        .perform(
            patch("/api/auth/admin/professionals/approval")
                .header("Authorization", bearer(List.of("REVIEWER")))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"doc@hospital.example\",\"approved\":true}"))
        .andExpect(status().isForbidden());
  }

  @Test
  void adminIsAllowedAndDelegatesToTheSharedActivationLogic() throws Exception {
    when(authService.approveProfessional(any(), eq("doc@hospital.example"), eq(true)))
        .thenReturn(
            new UserResponse(
                "id-1",
                "Dr Doc",
                "doc@hospital.example",
                "MN-1",
                "Spine",
                "Hospital",
                List.of("DOCTOR", "REVIEWER"),
                true,
                true,
                false,
                true));

    mockMvc
        .perform(
            patch("/api/auth/admin/professionals/approval")
                .header("Authorization", bearer(List.of("ADMIN")))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"doc@hospital.example\",\"approved\":true}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.roles[0]").value("DOCTOR"))
        .andExpect(jsonPath("$.roles[1]").value("REVIEWER"));
  }

  @Test
  void approvingTrueOnAnAdminAccountReturnsAdminAccountProtected() throws Exception {
    when(authService.approveProfessional(any(), eq("admin@hospital.example"), eq(true)))
        .thenThrow(new ar.edu.uade.pfi.backend.auth.AdminAccountProtectedException());

    mockMvc
        .perform(
            patch("/api/auth/admin/professionals/approval")
                .header("Authorization", bearer(List.of("ADMIN")))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"admin@hospital.example\",\"approved\":true}"))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.code").value("ADMIN_ACCOUNT_PROTECTED"));
  }

  @Test
  void approvingFalseOnAnAdminAccountReturnsAdminAccountProtected() throws Exception {
    when(authService.approveProfessional(any(), eq("admin@hospital.example"), eq(false)))
        .thenThrow(new ar.edu.uade.pfi.backend.auth.AdminAccountProtectedException());

    mockMvc
        .perform(
            patch("/api/auth/admin/professionals/approval")
                .header("Authorization", bearer(List.of("ADMIN")))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"admin@hospital.example\",\"approved\":false}"))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.code").value("ADMIN_ACCOUNT_PROTECTED"));
  }

  private String bearer(List<String> roles) {
    return "Bearer "
        + tokenService.issueAccessToken(
            new ar.edu.uade.pfi.backend.auth.DoctorAccount(
                "user-1",
                "Test User",
                "user@example.com",
                "hash",
                "",
                "",
                "",
                roles,
                java.time.Instant.now(),
                true,
                true,
                false,
                true));
  }
}
