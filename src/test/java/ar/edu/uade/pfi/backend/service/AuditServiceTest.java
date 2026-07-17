package ar.edu.uade.pfi.backend.service;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
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
import ar.edu.uade.pfi.backend.domain.DomainAuditEvent;
import ar.edu.uade.pfi.backend.domain.Study;
import ar.edu.uade.pfi.backend.dto.AiInputResponseDto;
import ar.edu.uade.pfi.backend.dto.MultiplanarRunResponseDto;
import ar.edu.uade.pfi.backend.repository.PostgresStudyRepository;
import ar.edu.uade.pfi.backend.service.ReviewStoreService;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
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
            .standaloneSetup(new AiBackendController(new AiBackendService(ai, org.mockito.Mockito.mock(ReviewStoreService.class), auditService)))
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

    private MultiplanarRunResponseDto multiplanarResponse() {
        return new MultiplanarRunResponseDto(
            "multi-audit-run",
            "trace-audit-run",
            "real_baseline",
            new MultiplanarRunResponseDto.PlanesDto(
                new MultiplanarRunResponseDto.PlaneDto(
                    "run-sag-audit",
                    "sagittal",
                    "sagittal_spider",
                    "completed",
                    "real_baseline",
                    Map.of("artifactHash", "sha256:sag-audit"),
                    List.of(),
                    List.of(),
                    Map.of("canalAreaMm2", 82.4),
                    Map.of("quality", 0.91),
                    Map.of("overlay", "overlay.png")
                ),
                new MultiplanarRunResponseDto.PlaneDto(
                    "run-ax-audit",
                    "axial",
                    "axial_t2_alkafri",
                    "completed",
                    "real_baseline",
                    Map.of("artifactHash", "sha256:ax-audit"),
                    List.of(),
                    List.of(),
                    Map.of("leftForamenMm", 3.1),
                    Map.of("quality", 0.88),
                    Map.of("maskPreview", "mask-preview.png")
                )
            ),
            Map.of("workspace", "workspace.json"),
            Map.of("status", "pendiente")
        );
    }
}
