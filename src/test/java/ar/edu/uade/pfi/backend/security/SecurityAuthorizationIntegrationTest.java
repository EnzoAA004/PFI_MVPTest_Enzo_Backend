package ar.edu.uade.pfi.backend.security;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import ar.edu.uade.pfi.backend.auth.AuthFilter;
import ar.edu.uade.pfi.backend.auth.RoleAuthorizationService;
import ar.edu.uade.pfi.backend.auth.TokenService;
import ar.edu.uade.pfi.backend.client.AiServiceOperations;
import ar.edu.uade.pfi.backend.config.ApiExceptionHandler;
import ar.edu.uade.pfi.backend.config.CorsResponseFilter;
import ar.edu.uade.pfi.backend.controller.AiBackendController;
import ar.edu.uade.pfi.backend.controller.AiMultiplanarController;
import ar.edu.uade.pfi.backend.controller.StudyController;
import ar.edu.uade.pfi.backend.controller.SystemController;
import ar.edu.uade.pfi.backend.domain.CanonicalMultiplanarRun;
import ar.edu.uade.pfi.backend.dto.StudyListResponseDto;
import ar.edu.uade.pfi.backend.service.AiBackendService;
import ar.edu.uade.pfi.backend.service.AuditService;
import ar.edu.uade.pfi.backend.service.ProfessionalAccessAuditService;
import ar.edu.uade.pfi.backend.service.StudyRunService;
import ar.edu.uade.pfi.backend.service.StudyWorklistService;
import ar.edu.uade.pfi.backend.service.SystemDiagnosticsService;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/**
 * Covers P10-A section 14: anonymous/PENDING_APPROVAL/professional/ADMIN access across
 * representative endpoints, JWT edge cases, CORS, and the standardized 401/403 body
 * shape. Uses the project's existing standaloneSetup + Mockito convention (there is no
 * @SpringBootTest anywhere in this codebase) with the *real* AuthFilter/TokenService/
 * RoleAuthorizationService wired in front of mocked service dependencies.
 */
class SecurityAuthorizationIntegrationTest {
    private static final String SECRET = "test-only-secret-at-least-32-bytes-long!!";
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final TokenService tokenService = new TokenService(objectMapper, SECRET, 3600);
    private final AuditService auditService = new AuditService(new ar.edu.uade.pfi.backend.repository.InMemoryStudyRepository());
    private final RoleAuthorizationService authorizationService = new RoleAuthorizationService(auditService);
    private AiServiceOperations aiServiceClient;
    private AiBackendService aiBackendService;
    private StudyWorklistService studyWorklistService;
    private SystemDiagnosticsService systemDiagnosticsService;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        aiServiceClient = mock(AiServiceOperations.class);
        aiBackendService = mock(AiBackendService.class);
        studyWorklistService = mock(StudyWorklistService.class);
        systemDiagnosticsService = mock(SystemDiagnosticsService.class);

        StudyController studyController = new StudyController(
            studyWorklistService,
            mock(StudyRunService.class),
            mock(ProfessionalAccessAuditService.class),
            authorizationService
        );
        AiMultiplanarController multiplanarController = new AiMultiplanarController(aiServiceClient);
        AiBackendController backendController = new AiBackendController(aiBackendService, authorizationService);
        SystemController systemController = new SystemController(systemDiagnosticsService, authorizationService);

        mockMvc = MockMvcBuilders
            .standaloneSetup(studyController, multiplanarController, backendController, systemController)
            .addFilter(new AuthFilter(
                tokenService,
                new ar.edu.uade.pfi.backend.auth.AuthAccountStateService(mock(ar.edu.uade.pfi.backend.auth.PostgresAuthStoreService.class), new org.springframework.mock.env.MockEnvironment()),
                true, false, new org.springframework.mock.env.MockEnvironment()))
            .addFilter(new CorsResponseFilter("https://allowed.example.com", "https://*.preview.example.com"))
            .setControllerAdvice(new ApiExceptionHandler(auditService))
            .build();
    }

    // ---- A. anonymous ----

    @Nested
    class Anonymous {
        @Test
        void studiesRequiresAuthentication() throws Exception {
            mockMvc.perform(get("/api/studies"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("AUTHENTICATION_REQUIRED"));
        }

        @Test
        void multiplanarRunRequiresAuthentication() throws Exception {
            mockMvc.perform(post("/api/ai/multiplanar/run")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{\"caseId\":\"CASE-1\"}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("AUTHENTICATION_REQUIRED"));
        }

        @Test
        void assetRequiresAuthentication() throws Exception {
            mockMvc.perform(get("/api/ai/assets/run-1/sagittal/overlay.png"))
                .andExpect(status().isUnauthorized());
        }

        @Test
        void adminDiagnosticsRequiresAuthentication() throws Exception {
            mockMvc.perform(get("/api/system/diagnostics"))
                .andExpect(status().isUnauthorized());
        }

        @Test
        void emptyBearerHeaderIsUnauthorized() throws Exception {
            mockMvc.perform(get("/api/studies").header("Authorization", "Bearer "))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("AUTHENTICATION_REQUIRED"));
        }
    }

    // ---- B. PENDING_APPROVAL ----

    @Nested
    class PendingApproval {
        @Test
        void studiesForbidden() throws Exception {
            mockMvc.perform(get("/api/studies").header("Authorization", bearer(List.of("PENDING_APPROVAL"))))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("ACCESS_DENIED"));
        }

        @Test
        void multiplanarRunForbidden() throws Exception {
            mockMvc.perform(post("/api/ai/multiplanar/run")
                    .header("Authorization", bearer(List.of("PENDING_APPROVAL")))
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{\"caseId\":\"CASE-1\"}"))
                .andExpect(status().isForbidden());
        }

        @Test
        void adminDiagnosticsForbidden() throws Exception {
            mockMvc.perform(get("/api/system/diagnostics").header("Authorization", bearer(List.of("PENDING_APPROVAL"))))
                .andExpect(status().isForbidden());
        }
    }

    // ---- C. approved professional ----

    @Nested
    class ApprovedProfessional {
        @Test
        void studiesAllowed() throws Exception {
            when(studyWorklistService.listStudies()).thenReturn(new StudyListResponseDto("ok", "test", "database", List.of(), Map.of(), true, true));
            mockMvc.perform(get("/api/studies").header("Authorization", bearer(List.of("DOCTOR", "REVIEWER"))))
                .andExpect(status().isOk());
        }

        @Test
        void multiplanarRunAllowed() throws Exception {
            when(aiServiceClient.runMultiplanar(any())).thenReturn(minimalCanonicalRun());
            mockMvc.perform(post("/api/ai/multiplanar/run")
                    .header("Authorization", bearer(List.of("DOCTOR")))
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{\"caseId\":\"CASE-1\",\"sagittalInputId\":\"inp-1\"}"))
                .andExpect(status().isOk());
        }

        @Test
        void adminDiagnosticsForbidden() throws Exception {
            mockMvc.perform(get("/api/system/diagnostics").header("Authorization", bearer(List.of("DOCTOR", "REVIEWER"))))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("ACCESS_DENIED"));
        }
    }

    // ---- D. ADMIN ----

    @Nested
    class Admin {
        @Test
        void diagnosticsAllowed() throws Exception {
            when(systemDiagnosticsService.diagnostics()).thenReturn(Map.of("status", "ok"));
            mockMvc.perform(get("/api/system/diagnostics").header("Authorization", bearer(List.of("ADMIN"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ok"));
        }

        @Test
        void adminCanAlsoUseProfessionalEndpoints() throws Exception {
            when(studyWorklistService.listStudies()).thenReturn(new StudyListResponseDto("ok", "test", "database", List.of(), Map.of(), true, true));
            mockMvc.perform(get("/api/studies").header("Authorization", bearer(List.of("ADMIN"))))
                .andExpect(status().isOk());
        }
    }

    // ---- P10-A.1 §7: minimal public surface / warmup ADMIN-only ----

    @Nested
    class PublicSurfaceMatrix {
        @Test
        void anonymousSystemHealthIsPublicAndMinimal() throws Exception {
            mockMvc.perform(get("/api/system/health"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ok"));
        }

        @Test
        void anonymousAiHealthRequiresAuthentication() throws Exception {
            mockMvc.perform(get("/api/ai/health")).andExpect(status().isUnauthorized());
        }

        @Test
        void anonymousAiModelsRequiresAuthentication() throws Exception {
            mockMvc.perform(get("/api/ai/models")).andExpect(status().isUnauthorized());
        }

        @Test
        void anonymousWarmupRequiresAuthentication() throws Exception {
            mockMvc.perform(post("/api/system/warmup")).andExpect(status().isUnauthorized());
        }

        @Test
        void pendingApprovalIsForbiddenFromAiHealthModelsAndWarmup() throws Exception {
            mockMvc.perform(get("/api/ai/health").header("Authorization", bearer(List.of("PENDING_APPROVAL"))))
                .andExpect(status().isForbidden());
            mockMvc.perform(get("/api/ai/models").header("Authorization", bearer(List.of("PENDING_APPROVAL"))))
                .andExpect(status().isForbidden());
            mockMvc.perform(post("/api/system/warmup").header("Authorization", bearer(List.of("PENDING_APPROVAL"))))
                .andExpect(status().isForbidden());
        }

        @Test
        void approvedProfessionalCanReadAiHealthAndModelsButNotWarmup() throws Exception {
            when(aiBackendService.health()).thenReturn(Map.of("status", "ok"));
            when(aiBackendService.models()).thenReturn(Map.of());
            mockMvc.perform(get("/api/ai/health").header("Authorization", bearer(List.of("DOCTOR"))))
                .andExpect(status().isOk());
            mockMvc.perform(get("/api/ai/models").header("Authorization", bearer(List.of("DOCTOR"))))
                .andExpect(status().isOk());
            mockMvc.perform(post("/api/system/warmup").header("Authorization", bearer(List.of("DOCTOR"))))
                .andExpect(status().isForbidden());
        }

        @Test
        void adminCanTriggerWarmup() throws Exception {
            when(systemDiagnosticsService.warmup()).thenReturn(Map.of("status", "ok"));
            mockMvc.perform(post("/api/system/warmup").header("Authorization", bearer(List.of("ADMIN"))))
                .andExpect(status().isOk());
        }
    }

    // ---- P10-A.1 §8: readiness/models-verify are ADMIN-only technical diagnostics ----

    @Nested
    class ReadinessAndVerifyAreAdminOnly {
        @Test
        void anonymousIsUnauthorized() throws Exception {
            mockMvc.perform(get("/api/ai/readiness")).andExpect(status().isUnauthorized());
            mockMvc.perform(get("/api/ai/models/verify")).andExpect(status().isUnauthorized());
        }

        @Test
        void approvedProfessionalIsForbidden() throws Exception {
            mockMvc.perform(get("/api/ai/readiness").header("Authorization", bearer(List.of("DOCTOR"))))
                .andExpect(status().isForbidden());
            mockMvc.perform(get("/api/ai/models/verify").header("Authorization", bearer(List.of("DOCTOR"))))
                .andExpect(status().isForbidden());
        }

        @Test
        void adminIsAllowed() throws Exception {
            when(aiBackendService.readiness()).thenReturn(Map.of("status", "ok"));
            when(aiBackendService.verifyModels()).thenReturn(Map.of("status", "ok"));
            mockMvc.perform(get("/api/ai/readiness").header("Authorization", bearer(List.of("ADMIN"))))
                .andExpect(status().isOk());
            mockMvc.perform(get("/api/ai/models/verify").header("Authorization", bearer(List.of("ADMIN"))))
                .andExpect(status().isOk());
        }
    }

    // ---- E. JWT edge cases ----

    @Nested
    class JwtEdgeCases {
        @Test
        void expiredTokenIsUnauthorized() throws Exception {
            TokenService expiring = new TokenService(objectMapper, SECRET, -10);
            String token = expiring.issueAccessToken(account(List.of("DOCTOR")));
            mockMvc.perform(get("/api/studies").header("Authorization", "Bearer " + token))
                .andExpect(status().isUnauthorized());
        }

        @Test
        void invalidSignatureIsUnauthorized() throws Exception {
            String token = bearer(List.of("DOCTOR")).substring("Bearer ".length());
            String[] parts = token.split("\\.");
            String tampered = parts[0] + "." + parts[1] + "." + "tamperedSignatureXX";
            mockMvc.perform(get("/api/studies").header("Authorization", "Bearer " + tampered))
                .andExpect(status().isUnauthorized());
        }

        @Test
        void malformedTokenIsUnauthorized() throws Exception {
            mockMvc.perform(get("/api/studies").header("Authorization", "Bearer not-a-jwt"))
                .andExpect(status().isUnauthorized());
        }

        @Test
        void tokenWithoutSubjectIsUnauthorized() throws Exception {
            String token = craftToken(Map.of(
                "roles", List.of("DOCTOR"),
                "exp", Instant.now().plusSeconds(3600).getEpochSecond()
            ));
            mockMvc.perform(get("/api/studies").header("Authorization", "Bearer " + token))
                .andExpect(status().isUnauthorized());
        }

        @Test
        void tokenWithoutAuthoritiesIsDeniedNotGrantedDefault() throws Exception {
            String token = craftToken(Map.of(
                "sub", "user-without-roles",
                "exp", Instant.now().plusSeconds(3600).getEpochSecond()
            ));
            when(studyWorklistService.listStudies()).thenReturn(new StudyListResponseDto("ok", "test", "database", List.of(), Map.of(), true, true));
            // Authenticated (valid signature+subject), but no ADMIN authority granted by default.
            mockMvc.perform(get("/api/system/diagnostics").header("Authorization", "Bearer " + token))
                .andExpect(status().isForbidden());
        }
    }

    // ---- F. CORS ----

    @Nested
    class Cors {
        @Test
        void allowedOriginGetsEchoedBack() throws Exception {
            when(studyWorklistService.listStudies()).thenReturn(new StudyListResponseDto("ok", "test", "database", List.of(), Map.of(), true, true));
            mockMvc.perform(get("/api/studies")
                    .header("Authorization", bearer(List.of("DOCTOR")))
                    .header("Origin", "https://allowed.example.com"))
                .andExpect(status().isOk())
                .andExpect(header().string("Access-Control-Allow-Origin", "https://allowed.example.com"));
        }

        @Test
        void unconfiguredOriginGetsNoAllowOriginHeader() throws Exception {
            when(studyWorklistService.listStudies()).thenReturn(new StudyListResponseDto("ok", "test", "database", List.of(), Map.of(), true, true));
            mockMvc.perform(get("/api/studies")
                    .header("Authorization", bearer(List.of("DOCTOR")))
                    .header("Origin", "https://evil.example.com"))
                .andExpect(header().doesNotExist("Access-Control-Allow-Origin"));
        }

        @Test
        void preflightForAllowedOriginDoesNotRequireAuthentication() throws Exception {
            mockMvc.perform(options("/api/studies")
                    .header("Origin", "https://allowed.example.com")
                    .header("Access-Control-Request-Method", "GET"))
                .andExpect(status().isNoContent())
                .andExpect(header().string("Access-Control-Allow-Origin", "https://allowed.example.com"));
        }

        @Test
        void preflightForRejectedOriginIsForbidden() throws Exception {
            mockMvc.perform(options("/api/studies")
                    .header("Origin", "https://evil.example.com")
                    .header("Access-Control-Request-Method", "GET"))
                .andExpect(status().isForbidden());
        }

        @Test
        void wildcardPatternMatchesPreviewSubdomain() throws Exception {
            when(studyWorklistService.listStudies()).thenReturn(new StudyListResponseDto("ok", "test", "database", List.of(), Map.of(), true, true));
            mockMvc.perform(get("/api/studies")
                    .header("Authorization", bearer(List.of("DOCTOR")))
                    .header("Origin", "https://pr-42.preview.example.com"))
                .andExpect(header().string("Access-Control-Allow-Origin", "https://pr-42.preview.example.com"));
        }
    }

    // ---- G. response body safety ----

    @Nested
    class ResponseSafety {
        @Test
        void unauthorizedBodyNeverLeaksTokenOrStackTrace() throws Exception {
            String body = mockMvc.perform(get("/api/studies").header("Authorization", "Bearer garbage"))
                .andReturn().getResponse().getContentAsString();
            org.junit.jupiter.api.Assertions.assertFalse(body.contains("garbage"));
            org.junit.jupiter.api.Assertions.assertFalse(body.toLowerCase().contains("exception"));
            org.junit.jupiter.api.Assertions.assertFalse(body.toLowerCase().contains(SECRET.toLowerCase()));
        }

        @Test
        void forbiddenBodyMatchesStandardShape() throws Exception {
            mockMvc.perform(get("/api/system/diagnostics").header("Authorization", bearer(List.of("DOCTOR"))))
                .andExpect(jsonPath("$.status").value("error"))
                .andExpect(jsonPath("$.code").value("ACCESS_DENIED"))
                .andExpect(jsonPath("$.traceId").exists())
                .andExpect(jsonPath("$.timestamp").exists());
        }
    }

    private String bearer(List<String> roles) {
        return "Bearer " + tokenService.issueAccessToken(account(roles));
    }

    private ar.edu.uade.pfi.backend.auth.DoctorAccount account(List<String> roles) {
        return new ar.edu.uade.pfi.backend.auth.DoctorAccount(
            "user-1", "Test User", "user@example.com", "hash",
            "", "", "", roles, Instant.now(), true, true, false, true
        );
    }

    private CanonicalMultiplanarRun minimalCanonicalRun() {
        return new CanonicalMultiplanarRun(
            "multiplanar_run_ready", "multiplanar-run-v1", "multi-1", "trace-1", "CASE-1",
            "sagittal_only", "mixed", "mixed",
            List.of("sagittal"), List.of("sagittal"), false, null, Map.of(), Map.of(),
            Map.of(), Map.of(), Map.of(),
            new CanonicalMultiplanarRun.Governance(true, true, true, false)
        );
    }

    /** Builds a raw JWT with the same HS256 scheme as TokenService, for edge-case payloads TokenService itself never issues. */
    private String craftToken(Map<String, Object> payload) throws Exception {
        Map<String, Object> header = Map.of("alg", "HS256", "typ", "JWT");
        String encodedHeader = base64Json(header);
        String encodedPayload = base64Json(payload);
        String signature = sign(encodedHeader + "." + encodedPayload);
        return encodedHeader + "." + encodedPayload + "." + signature;
    }

    private String base64Json(Map<String, Object> value) throws Exception {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(objectMapper.writeValueAsBytes(new LinkedHashMap<>(value)));
    }

    private String sign(String value) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(SECRET.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        return Base64.getUrlEncoder().withoutPadding().encodeToString(mac.doFinal(value.getBytes(StandardCharsets.UTF_8)));
    }
}
