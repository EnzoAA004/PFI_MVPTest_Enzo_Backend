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
import ar.edu.uade.pfi.backend.auth.dto.AuthDtos.ProfessionalActivationResponse;
import ar.edu.uade.pfi.backend.repository.InMemoryStudyRepository;
import ar.edu.uade.pfi.backend.service.AuditService;
import ar.edu.uade.pfi.backend.web.error.ApiExceptionHandler;
import ar.edu.uade.pfi.backend.web.filter.CorsResponseFilter;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/**
 * P10-A.2 §12: PATCH /api/auth/admin/professionals/activation must require ADMIN via AuthFilter
 * (401 anonymous) + RoleAuthorizationService (403 non-admin), and never accept role-elevating
 * fields from the request body.
 */
class ProfessionalActivationControllerTest {
  private static final String SECRET = "controller-test-secret-at-least-32-bytes-long!!";
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
            .setMessageConverters(
                new org.springframework.http.converter.json.MappingJackson2HttpMessageConverter(
                    objectMapper))
            .addFilter(
                new AuthFilter(
                    tokenService,
                    new ar.edu.uade.pfi.backend.auth.AuthAccountStateService(
                        mock(ar.edu.uade.pfi.backend.auth.PostgresAuthStoreService.class),
                        new MockEnvironment()),
                    true,
                    false,
                    new MockEnvironment()))
            .addFilter(new CorsResponseFilter("", ""))
            .setControllerAdvice(new ApiExceptionHandler(auditService))
            .build();
  }

  @Test
  void anonymousIsUnauthorized() throws Exception {
    mockMvc
        .perform(
            patch("/api/auth/admin/professionals/activation")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"doc@hospital.example\",\"activated\":true}"))
        .andExpect(status().isUnauthorized())
        .andExpect(jsonPath("$.code").value("AUTHENTICATION_REQUIRED"));
  }

  @Test
  void pendingApprovalIsForbidden() throws Exception {
    mockMvc
        .perform(
            patch("/api/auth/admin/professionals/activation")
                .header("Authorization", bearer(List.of("PENDING_APPROVAL")))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"doc@hospital.example\",\"activated\":true}"))
        .andExpect(status().isForbidden());
  }

  @Test
  void doctorIsForbidden() throws Exception {
    mockMvc
        .perform(
            patch("/api/auth/admin/professionals/activation")
                .header("Authorization", bearer(List.of("DOCTOR")))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"doc@hospital.example\",\"activated\":true}"))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.code").value("ACCESS_DENIED"));
  }

  @Test
  void reviewerIsForbidden() throws Exception {
    mockMvc
        .perform(
            patch("/api/auth/admin/professionals/activation")
                .header("Authorization", bearer(List.of("REVIEWER")))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"doc@hospital.example\",\"activated\":true}"))
        .andExpect(status().isForbidden());
  }

  @Test
  void adminIsAllowed() throws Exception {
    when(authService.activateProfessional(any(), eq("doc@hospital.example"), eq(true)))
        .thenReturn(
            new ProfessionalActivationResponse(
                "doc@hospital.example", "activated", true, true, List.of("DOCTOR", "REVIEWER")));

    mockMvc
        .perform(
            patch("/api/auth/admin/professionals/activation")
                .header("Authorization", bearer(List.of("ADMIN")))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"doc@hospital.example\",\"activated\":true}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("activated"))
        .andExpect(jsonPath("$.roles[0]").value("DOCTOR"));
  }

  @Test
  void payloadWithRolesFieldIsRejectedWith400AndNeverGrantsAdmin() throws Exception {
    mockMvc
        .perform(
            patch("/api/auth/admin/professionals/activation")
                .header("Authorization", bearer(List.of("ADMIN")))
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    "{\"email\":\"doc@hospital.example\",\"activated\":true,\"roles\":[\"ADMIN\"]}"))
        .andExpect(status().isBadRequest());

    verify(authService, never()).activateProfessional(any(), anyString(), anyBoolean());
  }

  @Test
  void payloadWithAdminFlagIsRejectedWith400AndNeverGrantsAdmin() throws Exception {
    mockMvc
        .perform(
            patch("/api/auth/admin/professionals/activation")
                .header("Authorization", bearer(List.of("ADMIN")))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"doc@hospital.example\",\"activated\":true,\"admin\":true}"))
        .andExpect(status().isBadRequest());

    verify(authService, never()).activateProfessional(any(), anyString(), anyBoolean());
  }

  @Test
  void activatingAnAdminAccountReturnsAdminAccountProtected() throws Exception {
    when(authService.activateProfessional(any(), eq("admin@hospital.example"), eq(true)))
        .thenThrow(new ar.edu.uade.pfi.backend.auth.exception.AdminAccountProtectedException());

    mockMvc
        .perform(
            patch("/api/auth/admin/professionals/activation")
                .header("Authorization", bearer(List.of("ADMIN")))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"admin@hospital.example\",\"activated\":true}"))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.code").value("ADMIN_ACCOUNT_PROTECTED"));
  }

  @Test
  void deactivatingAnAdminAccountReturnsAdminAccountProtected() throws Exception {
    when(authService.activateProfessional(any(), eq("admin@hospital.example"), eq(false)))
        .thenThrow(new ar.edu.uade.pfi.backend.auth.exception.AdminAccountProtectedException());

    mockMvc
        .perform(
            patch("/api/auth/admin/professionals/activation")
                .header("Authorization", bearer(List.of("ADMIN")))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"admin@hospital.example\",\"activated\":false}"))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.code").value("ADMIN_ACCOUNT_PROTECTED"));
  }

  @Test
  void payloadWithPasswordFieldIsRejected() throws Exception {
    mockMvc
        .perform(
            patch("/api/auth/admin/professionals/activation")
                .header("Authorization", bearer(List.of("ADMIN")))
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    "{\"email\":\"doc@hospital.example\",\"activated\":true,\"password\":\"whatever\"}"))
        .andExpect(status().isBadRequest());
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
