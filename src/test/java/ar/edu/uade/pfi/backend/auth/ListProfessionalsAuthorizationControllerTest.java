package ar.edu.uade.pfi.backend.auth;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import ar.edu.uade.pfi.backend.auth.dto.AuthDtos.UserResponse;
import ar.edu.uade.pfi.backend.repository.InMemoryStudyRepository;
import ar.edu.uade.pfi.backend.service.AuditService;
import ar.edu.uade.pfi.backend.web.error.ApiExceptionHandler;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/**
 * P10-B.2: GET /api/auth/admin/professionals now enforces the controller-level
 * RoleAuthorizationService.requireAdmin gate in addition to AuthService's own internal check — this
 * proves the HTTP-layer matrix (anonymous->401, DOCTOR/REVIEWER->403, ADMIN->200) holds even if the
 * service-level check were ever removed or bypassed.
 */
class ListProfessionalsAuthorizationControllerTest {
  private static final String SECRET = "list-professionals-authz-secret-32-bytes!!";
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
                    new AuthAccountStateService(
                        mock(PostgresAuthStoreService.class), new MockEnvironment()),
                    true,
                    false,
                    new MockEnvironment()))
            .setControllerAdvice(new ApiExceptionHandler(auditService))
            .build();
  }

  @Test
  void anonymousIsUnauthorized() throws Exception {
    mockMvc
        .perform(get("/api/auth/admin/professionals"))
        .andExpect(status().isUnauthorized())
        .andExpect(jsonPath("$.code").value("AUTHENTICATION_REQUIRED"));
    verify(authService, never()).listProfessionals(any());
  }

  @Test
  void pendingApprovalIsForbidden() throws Exception {
    mockMvc
        .perform(
            get("/api/auth/admin/professionals")
                .header("Authorization", bearer(List.of("PENDING_APPROVAL"))))
        .andExpect(status().isForbidden());
  }

  @Test
  void doctorIsForbidden() throws Exception {
    mockMvc
        .perform(
            get("/api/auth/admin/professionals").header("Authorization", bearer(List.of("DOCTOR"))))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.code").value("ACCESS_DENIED"));
    verify(authService, never()).listProfessionals(any());
  }

  @Test
  void reviewerIsForbidden() throws Exception {
    mockMvc
        .perform(
            get("/api/auth/admin/professionals")
                .header("Authorization", bearer(List.of("REVIEWER"))))
        .andExpect(status().isForbidden());
  }

  @Test
  void adminIsAllowed() throws Exception {
    when(authService.listProfessionals(any()))
        .thenReturn(
            List.of(
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
                    true)));

    mockMvc
        .perform(
            get("/api/auth/admin/professionals").header("Authorization", bearer(List.of("ADMIN"))))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$[0].email").value("doc@hospital.example"));
  }

  private String bearer(List<String> roles) {
    return "Bearer "
        + tokenService.issueAccessToken(
            new DoctorAccount(
                "user-1",
                "Test User",
                "user@example.com",
                "hash",
                "",
                "",
                "",
                roles,
                Instant.now(),
                true,
                true,
                false,
                true));
  }
}
