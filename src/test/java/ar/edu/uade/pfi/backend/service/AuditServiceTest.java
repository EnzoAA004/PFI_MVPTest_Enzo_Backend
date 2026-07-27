package ar.edu.uade.pfi.backend.service;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import ar.edu.uade.pfi.backend.client.AiServiceOperations;
import ar.edu.uade.pfi.backend.config.ApiExceptionHandler;
import ar.edu.uade.pfi.backend.controller.AiBackendController;
import ar.edu.uade.pfi.backend.controller.AiMultiplanarController;
import ar.edu.uade.pfi.backend.controller.AiRunReviewController;
import ar.edu.uade.pfi.backend.domain.CanonicalMultiplanarRun;
import ar.edu.uade.pfi.backend.domain.CanonicalPlaneRun;
import ar.edu.uade.pfi.backend.domain.DomainAuditEvent;
import ar.edu.uade.pfi.backend.domain.Study;
import ar.edu.uade.pfi.backend.dto.AiInputResponseDto;
import ar.edu.uade.pfi.backend.repository.PostgresStudyRepository;
import ar.edu.uade.pfi.backend.service.ReviewStoreService;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers
class AuditServiceTest {
    @Container
    static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine")
        .withDatabaseName("pfi_be009")
        .withUsername("pfi")
        .withPassword("pfi");

    private PostgresStudyRepository repository;
    private AuditService auditService;

    @BeforeEach
    void setUp() {
        repository = new PostgresStudyRepository(
            new ObjectMapper(),
            postgres.getJdbcUrl() + "&user=" + postgres.getUsername() + "&password=" + postgres.getPassword(),
            true
        );
        auditService = new AuditService(repository);
    }

    @Test
    void recordsUploadRunReviewAndErrorAuditEventsWithoutSensitiveMetadata() throws Exception {
        AiServiceOperations ai = org.mockito.Mockito.mock(AiServiceOperations.class);
        when(ai.uploadInput(any(), eq("CASE-AUDIT"), eq("sagittal")))
            .thenReturn(new AiInputResponseDto("input-audit-sag", "CASE-AUDIT", "sagittal", "npy", 3));
        when(ai.runMultiplanar(any())).thenReturn(multiplanarResponse());

        MockMvc uploadMvc = MockMvcBuilders
            .standaloneSetup(new AiBackendController(new AiBackendService(ai, org.mockito.Mockito.mock(ReviewStoreService.class), auditService), org.mockito.Mockito.mock(ar.edu.uade.pfi.backend.auth.RoleAuthorizationService.class)))
            .setControllerAdvice(new ApiExceptionHandler(auditService))
            .build();
        MockMultipartFile file = new MockMultipartFile("file", "secret-case.npy", "application/octet-stream", new byte[] {1, 2, 3});

        uploadMvc.perform(multipart("/api/ai/inputs")
                .file(file)
                .param("caseId", "CASE-AUDIT")
                .param("plane", "sagittal"))
            .andExpect(status().isOk());

        uploadMvc.perform(multipart("/api/ai/inputs")
                .file(new MockMultipartFile("file", "C:\\secret\\token.exe", MediaType.APPLICATION_OCTET_STREAM_VALUE, new byte[] {1}))
                .param("caseId", "CASE-AUDIT")
                .param("plane", "sagittal")
                .header("X-Trace-Id", "trace-error-audit"))
            .andExpect(status().isBadRequest());

        MockMvc runMvc = MockMvcBuilders
            .standaloneSetup(new AiMultiplanarController(ai, null, auditService))
            .setControllerAdvice(new ApiExceptionHandler(auditService))
            .build();
        runMvc.perform(post("/api/ai/multiplanar/run")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {
                      "caseId": "CASE-AUDIT",
                      "sagittalInputId": "input-audit-sag",
                      "axialInputId": "input-audit-ax",
                      "allowContractFallback": true,
                      "metadata": {"inferenceMode": "real_baseline"}
                    }
                    """))
            .andExpect(status().isOk());

        seedRun();
        MockMvc reviewMvc = MockMvcBuilders
            .standaloneSetup(new AiRunReviewController(new RunReviewService(repository), auditService))
            .setControllerAdvice(new ApiExceptionHandler(auditService))
            .build();
        reviewMvc.perform(post("/api/ai/runs/multi-audit-review/review")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"reviewStatus":"accepted","reviewer":"dra-audit","comments":"Revision aceptada"}
                    """))
            .andExpect(status().isOk());

        List<DomainAuditEvent> runEvents = repository.findAuditEventsByTraceId("trace-audit-run");
        List<DomainAuditEvent> uploadEvents = repository.findAuditEventsByEntityId("input-audit-sag");
        List<DomainAuditEvent> reviewEvents = repository.findAuditEventsByEntityId("multi-audit-review");
        List<DomainAuditEvent> errorEvents = repository.findAuditEventsByTraceId("trace-error-audit");

        assertTrue(uploadEvents.stream().anyMatch(event -> event.action().equals("upload.input.completed")));
        assertTrue(runEvents.stream().anyMatch(event -> event.action().equals("multiplanar.run.completed")));
        assertTrue(reviewEvents.stream().anyMatch(event -> event.action().equals("review.updated")));
        assertTrue(errorEvents.stream().anyMatch(event -> event.action().equals("error.http")));

        String allMetadata = List.of(uploadEvents, runEvents, reviewEvents, errorEvents).toString();
        assertFalse(allMetadata.toLowerCase().contains("token"));
        assertFalse(allMetadata.contains("C:\\"));
        assertFalse(allMetadata.contains("/tmp/"));
        assertFalse(allMetadata.contains("secret-case.npy"));
    }

    @Test
    void sanitizerDropsSensitiveKeysAndPathLikeValues() {
        Map<String, Object> safe = auditService.sanitize(Map.of(
            "token", "abc",
            "filePath", "C:\\secret\\case.dcm",
            "plane", "sagittal",
            "asset", "../mask.npy"
        ));

        assertFalse(safe.containsKey("token"));
        assertFalse(safe.containsKey("filePath"));
        assertTrue(safe.containsKey("plane"));
        assertTrue(String.valueOf(safe.get("asset")).contains("[redacted]"));
    }

    @Test
    void recordsSagittalOnlyRealBaselineAsSuccessfulWithoutAxialCompletion() throws Exception {
        AiServiceOperations ai = org.mockito.Mockito.mock(AiServiceOperations.class);
        when(ai.runMultiplanar(any())).thenReturn(sagittalOnlyResponse());

        MockMvc runMvc = MockMvcBuilders
            .standaloneSetup(new AiMultiplanarController(ai, null, auditService))
            .setControllerAdvice(new ApiExceptionHandler(auditService))
            .build();
        runMvc.perform(post("/api/ai/multiplanar/run")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {
                      "caseId": "CASE-AUDIT-SAG-ONLY",
                      "sagittalInputId": "input-audit-sag-only",
                      "allowContractFallback": false,
                      "metadata": {
                        "inferenceMode": "real_baseline",
                        "axialMode": "optional_not_provided"
                      }
                    }
                    """))
            .andExpect(status().isOk());

        List<DomainAuditEvent> events = repository.findAuditEventsByTraceId("trace-audit-sag-only");
        assertTrue(events.stream().anyMatch(event -> event.action().equals("multiplanar.real_baseline.completed")));
        DomainAuditEvent event = events.stream()
            .filter(item -> item.action().equals("multiplanar.real_baseline.completed"))
            .findFirst()
            .orElseThrow();
        assertEquals(true, event.metadata().get("sagittalInputIdPresent"));
        assertEquals(false, event.metadata().get("axialInputIdPresent"));
        assertEquals("real_baseline", event.metadata().get("sagittalInferenceMode"));
        assertEquals("", event.metadata().get("axialInferenceMode"));
        assertEquals(false, event.metadata().get("dualRunReady"));
    }

    private void seedRun() {
        StudyRunService service = new StudyRunService(repository);
        Study study = service.createStudy("CASE-AUDIT-REVIEW", "created");
        service.createRun(
            study,
            "multi-audit-review",
            "trace-audit-review",
            "real_baseline",
            "real_baseline",
            "sagittal_spider",
            "axial_t2_alkafri",
            "run-sag-audit",
            "run-ax-audit",
            Map.of("workspace", "workspace.json"),
            "completed"
        );
    }

    private CanonicalMultiplanarRun multiplanarResponse() {
        CanonicalPlaneRun sagittal = new CanonicalPlaneRun(
            "run-sag-audit", "sagittal", "completed", "real_baseline", false, null,
            Map.of("modelKey", "sagittal_spider", "artifactHash", "sha256:sag-audit"),
            Map.of(), Map.of(), List.of(), List.of(), List.of(), List.of(),
            List.of(Map.of("id", "canalAreaMm2", "value", 82.4)),
            Map.of("quality", 0.91)
        );
        CanonicalPlaneRun axial = new CanonicalPlaneRun(
            "run-ax-audit", "axial", "completed", "real_baseline", false, null,
            Map.of("modelKey", "axial_t2_alkafri", "artifactHash", "sha256:ax-audit"),
            Map.of(), Map.of(), List.of(), List.of(), List.of(), List.of(),
            List.of(Map.of("id", "leftForamenMm", "value", 3.1)),
            Map.of("quality", 0.88)
        );
        Map<String, CanonicalPlaneRun> planes = new LinkedHashMap<>();
        planes.put("sagittal", sagittal);
        planes.put("axial", axial);
        return new CanonicalMultiplanarRun(
            "multiplanar_run_ready", "multiplanar-run-v1", "multi-audit-run", "trace-audit-run", "CASE-AUDIT",
            "dual_plane_with_3d_context", "real_baseline", "real_baseline",
            List.of("sagittal", "axial"), List.of("sagittal", "axial"), false, null,
            Map.of(), planes, Map.of(), Map.of(), Map.of("status", "pendiente"),
            new CanonicalMultiplanarRun.Governance(true, true, true, false)
        );
    }

    private CanonicalMultiplanarRun sagittalOnlyResponse() {
        CanonicalPlaneRun sagittal = new CanonicalPlaneRun(
            "run-sag-audit-only", "sagittal", "completed", "real_baseline", false, null,
            Map.of(
                "modelKey", "sagittal_spider",
                "modelVersion", "sagittal-spider-final-v1",
                "artifactHash", MultiplanarRealBaselineContractValidator.SAGITTAL_ARTIFACT_HASH
            ),
            Map.of("inputId", "input-audit-sag-only"),
            Map.of(),
            List.of(Map.of("seriesId", "series-sag")),
            List.of(Map.of("assetName", "input.png"), Map.of("assetName", "overlay.png")),
            List.of(),
            List.of(Map.of("name", "L4_left_pedicle")),
            List.of(Map.of("id", "canalAreaMm2")),
            Map.of("confidence", 0.94)
        );
        Map<String, CanonicalPlaneRun> planes = new LinkedHashMap<>();
        planes.put("sagittal", sagittal);
        return new CanonicalMultiplanarRun(
            "multiplanar_run_ready", "multiplanar-run-v1", "multi-audit-sag-only", "trace-audit-sag-only", "CASE-AUDIT-SAG-ONLY",
            "sagittal_only_with_optional_axial", "real_baseline", "mixed",
            List.of("sagittal"), List.of("sagittal"), false, null,
            Map.of("sagittalRunReady", true, "axialRunReady", false, "dualRunReady", false),
            planes, Map.of(), Map.of(), Map.of("status", "pending"),
            new CanonicalMultiplanarRun.Governance(true, true, true, false)
        );
    }
}
