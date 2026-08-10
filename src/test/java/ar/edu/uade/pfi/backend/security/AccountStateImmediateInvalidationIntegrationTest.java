package ar.edu.uade.pfi.backend.security;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import ar.edu.uade.pfi.backend.auth.AuthAccountStateService;
import ar.edu.uade.pfi.backend.auth.AuthController;
import ar.edu.uade.pfi.backend.auth.AuthFilter;
import ar.edu.uade.pfi.backend.auth.AuthService;
import ar.edu.uade.pfi.backend.auth.PasswordHasher;
import ar.edu.uade.pfi.backend.auth.PostgresAuthStoreService;
import ar.edu.uade.pfi.backend.auth.RoleAuthorizationService;
import ar.edu.uade.pfi.backend.auth.TokenService;
import ar.edu.uade.pfi.backend.client.AiServiceOperations;
import ar.edu.uade.pfi.backend.controller.AiAssetController;
import ar.edu.uade.pfi.backend.controller.AiBackendController;
import ar.edu.uade.pfi.backend.controller.AiInputController;
import ar.edu.uade.pfi.backend.controller.AiMultiplanarController;
import ar.edu.uade.pfi.backend.controller.AiRunReviewController;
import ar.edu.uade.pfi.backend.controller.StudyController;
import ar.edu.uade.pfi.backend.controller.SystemController;
import ar.edu.uade.pfi.backend.domain.CanonicalMultiplanarRun;
import ar.edu.uade.pfi.backend.dto.RunReviewResponseDto;
import ar.edu.uade.pfi.backend.dto.StudyListResponseDto;
import ar.edu.uade.pfi.backend.repository.InMemoryStudyRepository;
import ar.edu.uade.pfi.backend.service.AiBackendService;
import ar.edu.uade.pfi.backend.service.AuditService;
import ar.edu.uade.pfi.backend.service.ProfessionalAccessAuditService;
import ar.edu.uade.pfi.backend.service.ReviewerAnnotationService;
import ar.edu.uade.pfi.backend.service.RunReviewService;
import ar.edu.uade.pfi.backend.service.StudyRunService;
import ar.edu.uade.pfi.backend.service.StudyWorklistService;
import ar.edu.uade.pfi.backend.service.SystemDiagnosticsService;
import ar.edu.uade.pfi.backend.web.error.ApiExceptionHandler;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.DriverManager;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * P10-A.2.1 §4/§5: the centerpiece proof that a still-unexpired access token stops granting access
 * the instant the account is deactivated, because AuthFilter now revalidates persisted state (via
 * AuthAccountStateService) on every protected request instead of trusting the JWT's own role claims
 * — this can only be demonstrated meaningfully against a real Postgres-backed, production-profile
 * stack.
 */
@Testcontainers(disabledWithoutDocker = true)
class AccountStateImmediateInvalidationIntegrationTest {
  @Container
  static final PostgreSQLContainer<?> postgres =
      new PostgreSQLContainer<>("postgres:16-alpine")
          .withDatabaseName("pfi_p10a21")
          .withUsername("pfi")
          .withPassword("pfi");

  private static final String SECRET = "integration-test-secret-at-least-32-bytes-long!!";

  private PostgresAuthStoreService store;
  private AuthService authService;
  private MockMvc mockMvc;
  private AiServiceOperations aiServiceClient;
  private AiBackendService aiBackendService;
  private StudyWorklistService studyWorklistService;
  private RunReviewService runReviewService;
  private TokenService tokenService;

  @BeforeEach
  void setUp() throws Exception {
    store =
        new PostgresAuthStoreService(
            "postgres",
            postgres.getJdbcUrl()
                + "&user="
                + postgres.getUsername()
                + "&password="
                + postgres.getPassword());
    truncate();

    ObjectMapper objectMapper = new ObjectMapper();
    tokenService = new TokenService(objectMapper, SECRET, 3600);
    PasswordHasher passwordHasher = new PasswordHasher();
    AuditService auditService = new AuditService(new InMemoryStudyRepository());
    RoleAuthorizationService authorizationService = new RoleAuthorizationService(auditService);
    MockEnvironment production = new MockEnvironment();
    production.setActiveProfiles("production");
    assertTrue(List.of(production.getActiveProfiles()).contains("production"));
    assertTrue(store.enabled());

    authService =
        new AuthService(
            passwordHasher, tokenService, store, false, 604800, production, false, auditService);
    AuthAccountStateService accountStateService = new AuthAccountStateService(store, production);
    AuthFilter authFilter =
        new AuthFilter(tokenService, accountStateService, true, false, production);

    AuthController authController =
        new AuthController(authService, auditService, authorizationService);

    aiServiceClient = mock(AiServiceOperations.class);
    aiBackendService = mock(AiBackendService.class);
    studyWorklistService = mock(StudyWorklistService.class);
    runReviewService = mock(RunReviewService.class);

    StudyController studyController =
        new StudyController(
            studyWorklistService,
            mock(StudyRunService.class),
            mock(ProfessionalAccessAuditService.class),
            authorizationService);
    AiMultiplanarController multiplanarController = new AiMultiplanarController(aiServiceClient);
    AiBackendController backendController =
        new AiBackendController(aiBackendService, authorizationService);
    AiInputController inputController = new AiInputController(aiBackendService);
    AiAssetController assetController = new AiAssetController(aiBackendService);
    AiRunReviewController runReviewController =
        new AiRunReviewController(
            runReviewService,
            auditService,
            authorizationService,
            mock(ReviewerAnnotationService.class));
    SystemController systemController =
        new SystemController(mock(SystemDiagnosticsService.class), authorizationService);

    mockMvc =
        MockMvcBuilders.standaloneSetup(
                authController,
                studyController,
                multiplanarController,
                backendController,
                inputController,
                assetController,
                runReviewController,
                systemController)
            .addFilter(authFilter)
            .setControllerAdvice(new ApiExceptionHandler(auditService))
            .build();
  }

  @Test
  void accessTokenStopsWorkingImmediatelyAfterDeactivation() throws Exception {
    // 1. Bootstrap an ADMIN directly (no HTTP bootstrap endpoint exists).
    seedAdmin("admin.p1021@hospital.example");
    String adminToken = login("admin.p1021@hospital.example");

    // 1b. Create + activate a professional account.
    registerPendingAccount("doc.p1021@hospital.example");
    mockMvc
        .perform(
            patch("/api/auth/admin/professionals/activation")
                .header("Authorization", "Bearer " + adminToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"doc.p1021@hospital.example\",\"activated\":true}"))
        .andExpect(status().isOk());

    // 2. Issue an access token for the now-activated professional.
    String docToken = login("doc.p1021@hospital.example");

    // 3. Confirm it currently works.
    when(studyWorklistService.listStudies())
        .thenReturn(
            new StudyListResponseDto("ok", "t", "database", List.of(), Map.of(), true, true));
    mockMvc
        .perform(get("/api/studies").header("Authorization", "Bearer " + docToken))
        .andExpect(status().isOk());

    // 4. ADMIN deactivates the professional.
    mockMvc
        .perform(
            patch("/api/auth/admin/professionals/activation")
                .header("Authorization", "Bearer " + adminToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"doc.p1021@hospital.example\",\"activated\":false}"))
        .andExpect(status().isOk());

    // 5/6. Reuse the EXACT SAME access token — it must be rejected everywhere now.
    mockMvc
        .perform(get("/api/studies").header("Authorization", "Bearer " + docToken))
        .andExpect(status().isForbidden());

    mockMvc
        .perform(
            post("/api/ai/inputs")
                .header("Authorization", "Bearer " + docToken)
                .contentType(MediaType.MULTIPART_FORM_DATA))
        .andExpect(status().isForbidden());

    when(aiServiceClient.runMultiplanar(org.mockito.ArgumentMatchers.any()))
        .thenReturn(minimalRun());
    mockMvc
        .perform(
            post("/api/ai/multiplanar/run")
                .header("Authorization", "Bearer " + docToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"caseId\":\"CASE-1\"}"))
        .andExpect(status().isForbidden());

    when(runReviewService.findReview("multi-1")).thenReturn(mock(RunReviewResponseDto.class));
    mockMvc
        .perform(get("/api/ai/runs/multi-1/review").header("Authorization", "Bearer " + docToken))
        .andExpect(status().isForbidden());

    when(aiBackendService.getAsset("run-1", "sagittal", "overlay.png"))
        .thenReturn(ResponseEntity.ok(new byte[0]));
    mockMvc
        .perform(
            get("/api/ai/assets/run-1/sagittal/overlay.png")
                .header("Authorization", "Bearer " + docToken))
        .andExpect(status().isForbidden());

    // 7. /me still answers, but reflects the CURRENT (now pending) state.
    mockMvc
        .perform(get("/api/auth/me").header("Authorization", "Bearer " + docToken))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.roles[0]").value("PENDING_APPROVAL"))
        .andExpect(jsonPath("$.approved").value(false));
  }

  /**
   * P10-A.2.2: the professional-activation flow can no longer touch ADMIN accounts at all — not
   * even one ADMIN targeting another. Both admins keep full, unaffected access to admin-only routes
   * after the blocked attempts.
   */
  @Test
  void twoAdminsNeitherCanDeactivateTheOtherThroughTheProfessionalFlow() throws Exception {
    seedAdmin("admin.a@hospital.example");
    seedAdmin("admin.b@hospital.example");
    String tokenA = login("admin.a@hospital.example");
    String tokenB = login("admin.b@hospital.example");

    mockMvc
        .perform(
            patch("/api/auth/admin/professionals/activation")
                .header("Authorization", "Bearer " + tokenB)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"admin.a@hospital.example\",\"activated\":false}"))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.code").value("ADMIN_ACCOUNT_PROTECTED"));

    mockMvc
        .perform(
            patch("/api/auth/admin/professionals/activation")
                .header("Authorization", "Bearer " + tokenA)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"admin.b@hospital.example\",\"activated\":false}"))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.code").value("ADMIN_ACCOUNT_PROTECTED"));

    mockMvc
        .perform(
            patch("/api/auth/admin/professionals/approval")
                .header("Authorization", "Bearer " + tokenA)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"admin.b@hospital.example\",\"approved\":false}"))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.code").value("ADMIN_ACCOUNT_PROTECTED"));

    // Neither account lost anything — both tokens still work on an admin-only route.
    mockMvc
        .perform(get("/api/system/diagnostics").header("Authorization", "Bearer " + tokenA))
        .andExpect(status().isOk());
    mockMvc
        .perform(get("/api/system/diagnostics").header("Authorization", "Bearer " + tokenB))
        .andExpect(status().isOk());
  }

  /**
   * P10-A.2.2 §2/§3/B: a row left with verified=false but stale DOCTOR/REVIEWER roles must never
   * grant access — the effective roles used for authorization come from verified/approved, not from
   * whatever roles are still sitting in the row.
   */
  @Test
  void unverifiedAccountWithStaleProfessionalRolesIsBlockedFromStudies() throws Exception {
    seedDoctorWithState("stale.unverified@hospital.example", false, true);
    String staleToken =
        tokenService.issueAccessToken(doctorAccount("stale.unverified@hospital.example"));

    mockMvc
        .perform(get("/api/studies").header("Authorization", "Bearer " + staleToken))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.code").value("ACCESS_DENIED"));
  }

  @Test
  void unapprovedAccountWithStaleProfessionalRolesIsBlockedFromStudies() throws Exception {
    seedDoctorWithState("stale.unapproved@hospital.example", true, false);
    String staleToken =
        tokenService.issueAccessToken(doctorAccount("stale.unapproved@hospital.example"));

    mockMvc
        .perform(get("/api/studies").header("Authorization", "Bearer " + staleToken))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.code").value("ACCESS_DENIED"));
  }

  @Test
  void unapprovedAdminIsBlockedFromDiagnostics() throws Exception {
    seedAdminWithState("stale.admin.unapproved@hospital.example", true, false);
    String staleToken =
        tokenService.issueAccessToken(adminAccount("stale.admin.unapproved@hospital.example"));

    mockMvc
        .perform(get("/api/system/diagnostics").header("Authorization", "Bearer " + staleToken))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.code").value("ACCESS_DENIED"));
  }

  @Test
  void unverifiedAdminIsBlockedFromDiagnostics() throws Exception {
    seedAdminWithState("stale.admin.unverified@hospital.example", false, true);
    String staleToken =
        tokenService.issueAccessToken(adminAccount("stale.admin.unverified@hospital.example"));

    mockMvc
        .perform(get("/api/system/diagnostics").header("Authorization", "Bearer " + staleToken))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.code").value("ACCESS_DENIED"));
  }

  @Test
  void meStillWorksForAnUnverifiedAccountAndReflectsCurrentState() throws Exception {
    seedDoctorWithState("stale.me@hospital.example", false, true);
    String staleToken = tokenService.issueAccessToken(doctorAccount("stale.me@hospital.example"));

    mockMvc
        .perform(get("/api/auth/me").header("Authorization", "Bearer " + staleToken))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.verified").value(false));
  }

  @Test
  void reactivatedAccountReusesItsExistingTokenButOnlyGetsCurrentRolesAfterRevalidation()
      throws Exception {
    seedAdmin("admin.reactivate@hospital.example");
    String adminToken = login("admin.reactivate@hospital.example");
    registerPendingAccount("doc.reactivate@hospital.example");
    mockMvc
        .perform(
            patch("/api/auth/admin/professionals/activation")
                .header("Authorization", "Bearer " + adminToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"doc.reactivate@hospital.example\",\"activated\":true}"))
        .andExpect(status().isOk());
    String docToken = login("doc.reactivate@hospital.example");

    mockMvc
        .perform(
            patch("/api/auth/admin/professionals/activation")
                .header("Authorization", "Bearer " + adminToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"doc.reactivate@hospital.example\",\"activated\":false}"))
        .andExpect(status().isOk());

    when(studyWorklistService.listStudies())
        .thenReturn(
            new StudyListResponseDto("ok", "t", "database", List.of(), Map.of(), true, true));
    mockMvc
        .perform(get("/api/studies").header("Authorization", "Bearer " + docToken))
        .andExpect(status().isForbidden());

    mockMvc
        .perform(
            patch("/api/auth/admin/professionals/activation")
                .header("Authorization", "Bearer " + adminToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"doc.reactivate@hospital.example\",\"activated\":true}"))
        .andExpect(status().isOk());

    // Same original access token, now revalidated against the reactivated row.
    mockMvc
        .perform(get("/api/studies").header("Authorization", "Bearer " + docToken))
        .andExpect(status().isOk());
  }

  private void seedDoctorWithState(String email, boolean verified, boolean approved) {
    store.saveAccount(
        new ar.edu.uade.pfi.backend.auth.DoctorAccount(
            java.util.UUID.randomUUID().toString(),
            "Dr. Stale",
            email,
            new PasswordHasher().hash("OriginalPass123!"),
            "MN-1",
            "Spine",
            "Hospital",
            List.of("DOCTOR", "REVIEWER"),
            java.time.Instant.now(),
            verified,
            approved,
            false,
            true));
  }

  private void seedAdminWithState(String email, boolean verified, boolean approved) {
    store.saveAccount(
        new ar.edu.uade.pfi.backend.auth.DoctorAccount(
            java.util.UUID.randomUUID().toString(),
            "Dr. Stale Admin",
            email,
            new PasswordHasher().hash("OriginalPass123!"),
            "MN-1",
            "Admin",
            "Hospital",
            List.of("ADMIN", "DOCTOR", "REVIEWER"),
            java.time.Instant.now(),
            verified,
            approved,
            false,
            true));
  }

  /**
   * Builds a token-signing source account with the given email's persisted id, so subject matching
   * in AuthAccountStateService succeeds.
   */
  private ar.edu.uade.pfi.backend.auth.DoctorAccount doctorAccount(String email) {
    var persisted = store.findByEmail(email).orElseThrow();
    return new ar.edu.uade.pfi.backend.auth.DoctorAccount(
        persisted.id(),
        persisted.fullName(),
        persisted.email(),
        persisted.passwordHash(),
        persisted.licenseNumber(),
        persisted.specialty(),
        persisted.institution(),
        List.of("DOCTOR", "REVIEWER"),
        persisted.createdAt(),
        persisted.verified(),
        persisted.approved(),
        false,
        true);
  }

  private ar.edu.uade.pfi.backend.auth.DoctorAccount adminAccount(String email) {
    var persisted = store.findByEmail(email).orElseThrow();
    return new ar.edu.uade.pfi.backend.auth.DoctorAccount(
        persisted.id(),
        persisted.fullName(),
        persisted.email(),
        persisted.passwordHash(),
        persisted.licenseNumber(),
        persisted.specialty(),
        persisted.institution(),
        List.of("ADMIN", "DOCTOR", "REVIEWER"),
        persisted.createdAt(),
        persisted.verified(),
        persisted.approved(),
        false,
        true);
  }

  private CanonicalMultiplanarRun minimalRun() {
    return new CanonicalMultiplanarRun(
        "multiplanar_run_ready",
        "multiplanar-run-v1",
        "multi-1",
        "trace-1",
        "CASE-1",
        "sagittal_only",
        "mixed",
        "mixed",
        List.of("sagittal"),
        List.of("sagittal"),
        false,
        null,
        Map.of(),
        Map.of(),
        Map.of(),
        Map.of(),
        Map.of(),
        new CanonicalMultiplanarRun.Governance(true, true, true, false));
  }

  private void seedAdmin(String email) {
    var account =
        new ar.edu.uade.pfi.backend.auth.DoctorAccount(
            java.util.UUID.randomUUID().toString(),
            "Dr. Admin",
            email,
            new PasswordHasher().hash("OriginalPass123!"),
            "MN-1",
            "Admin",
            "Hospital",
            List.of("ADMIN", "DOCTOR", "REVIEWER"),
            java.time.Instant.now(),
            true,
            true,
            false,
            true);
    store.saveAccount(account);
  }

  private void registerPendingAccount(String email) throws Exception {
    mockMvc.perform(
        post("/api/auth/register")
            .contentType(MediaType.APPLICATION_JSON)
            .content(
                "{\"fullName\":\"Dr New\",\"email\":\""
                    + email
                    + "\",\"password\":\"OriginalPass123!\"}"));
    // Verification isn't exercised here — institutional activation (below) sets
    // verified=true directly, matching the production flow where there's no email
    // provider (see docs/P10_A2_ADMIN_BOOTSTRAP_AND_ACTIVATION.md).
  }

  private String login(String email) throws Exception {
    String body =
        mockMvc
            .perform(
                post("/api/auth/login")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{\"email\":\"" + email + "\",\"password\":\"OriginalPass123!\"}"))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();
    return new ObjectMapper().readTree(body).get("accessToken").asText();
  }

  private void truncate() throws Exception {
    try (var connection =
            DriverManager.getConnection(
                postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
        var statement = connection.createStatement()) {
      statement.execute("TRUNCATE auth_refresh_tokens, doctor_accounts RESTART IDENTITY CASCADE");
    }
  }
}
