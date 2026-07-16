package ar.edu.uade.pfi.backend.controller;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import ar.edu.uade.pfi.backend.config.ApiExceptionHandler;
import ar.edu.uade.pfi.backend.domain.RunArtifact;
import ar.edu.uade.pfi.backend.domain.Study;
import ar.edu.uade.pfi.backend.repository.PostgresStudyRepository;
import ar.edu.uade.pfi.backend.service.RunReviewService;
import ar.edu.uade.pfi.backend.service.StudyRunService;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers
class AiRunReviewControllerTest {
    @Container
    static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine")
        .withDatabaseName("pfi_be007")
        .withUsername("pfi")
        .withPassword("pfi");

    private MockMvc mockMvc;
    private StudyRunService studyRunService;

    @BeforeEach
    void setUp() {
        PostgresStudyRepository repository = new PostgresStudyRepository(
            new ObjectMapper(),
            postgres.getJdbcUrl() + "&user=" + postgres.getUsername() + "&password=" + postgres.getPassword(),
            true
        );
        studyRunService = new StudyRunService(repository);
        RunReviewService reviewService = new RunReviewService(repository);
        mockMvc = MockMvcBuilders.standaloneSetup(new AiRunReviewController(reviewService))
            .setControllerAdvice(new ApiExceptionHandler())
            .build();
    }

    @Test
    void registersAndRetrievesProfessionalReviewWithCorrections() throws Exception {
        seedRun("multi-review-001", "trace-review-001");

        mockMvc.perform(post("/api/ai/runs/multi-review-001/review")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {
                      "reviewStatus": "observed",
                      "reviewer": "dra-demo",
                      "comments": "Medicion observada para ajuste academico.",
                      "corrections": [
                        {
                          "measurementId": "canalAreaMm2",
                          "label": "Area del canal",
                          "beforeValue": {"value": 82.4, "unit": "mm2"},
                          "afterValue": {"value": 85.1, "unit": "mm2"},
                          "comment": "Ajuste manual por borde parcial."
                        }
                      ]
                    }
                    """))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.multiplanarRunId").value("multi-review-001"))
            .andExpect(jsonPath("$.traceId").value("trace-review-001"))
            .andExpect(jsonPath("$.reviewStatus").value("observed"))
            .andExpect(jsonPath("$.reviewer").value("dra-demo"))
            .andExpect(jsonPath("$.reviewedAt").exists())
            .andExpect(jsonPath("$.corrections[0].measurementId").value("canalAreaMm2"))
            .andExpect(jsonPath("$.corrections[0].afterValue.value").value(85.1));

        mockMvc.perform(get("/api/ai/runs/multi-review-001/review"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.reviewStatus").value("observed"))
            .andExpect(jsonPath("$.comments").value("Medicion observada para ajuste academico."))
            .andExpect(jsonPath("$.corrections[0].label").value("Area del canal"));
    }

    @Test
    void updatesExistingReview() throws Exception {
        seedRun("multi-review-002", "trace-review-002");

        mockMvc.perform(post("/api/ai/runs/multi-review-002/review")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"reviewStatus":"observed","reviewer":"dra-demo","comments":"Primera revision"}
                    """))
            .andExpect(status().isOk());

        mockMvc.perform(put("/api/ai/runs/multi-review-002/review")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"reviewStatus":"accepted","reviewer":"dra-demo","comments":"Aceptado luego de revisar"}
                    """))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.reviewStatus").value("accepted"))
            .andExpect(jsonPath("$.comments").value("Aceptado luego de revisar"));
    }

    @Test
    void rejectsMissingRunInvalidStatusAndMissingReviewer() throws Exception {
        mockMvc.perform(post("/api/ai/runs/missing-run/review")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"reviewStatus":"accepted","reviewer":"dra-demo"}
                    """))
            .andExpect(status().isNotFound());

        seedRun("multi-review-003", "trace-review-003");

        mockMvc.perform(post("/api/ai/runs/multi-review-003/review")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"reviewStatus":"invalid","reviewer":"dra-demo"}
                    """))
            .andExpect(status().isBadRequest());

        mockMvc.perform(post("/api/ai/runs/multi-review-003/review")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"reviewStatus":"accepted","reviewer":""}
                    """))
            .andExpect(status().isBadRequest());
    }

    private void seedRun(String multiplanarRunId, String traceId) {
        Study study = studyRunService.createStudy("CASE-" + multiplanarRunId, "created");
        String runId = UUID.randomUUID().toString();
        Instant now = Instant.now();
        studyRunService.createRunWithId(
            runId,
            study,
            multiplanarRunId,
            traceId,
            "real_baseline",
            "real_baseline",
            "sagittal_spider",
            "axial_t2_alkafri",
            "sha256:sag",
            "sha256:ax",
            "run-sag-" + multiplanarRunId,
            "run-ax-" + multiplanarRunId,
            Map.of("workspace", "workspace.json"),
            Map.of("quality", Map.of("score", 0.92)),
            List.of(new RunArtifact(UUID.randomUUID().toString(), runId, "run-sag-" + multiplanarRunId, "sagittal", "overlay.png", "image/png", "overlay.png", now)),
            "completed",
            "pending",
            "",
            null,
            ""
        );
    }
}
