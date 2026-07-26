package ar.edu.uade.pfi.backend.controller;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.List;
import java.util.HashMap;
import java.util.Map;
import ar.edu.uade.pfi.backend.client.AiServiceOperations;
import ar.edu.uade.pfi.backend.domain.Study;
import ar.edu.uade.pfi.backend.repository.InMemoryStudyRepository;
import ar.edu.uade.pfi.backend.service.AiBackendService;
import ar.edu.uade.pfi.backend.service.PostgresReviewStoreService;
import ar.edu.uade.pfi.backend.service.ReviewStoreService;
import ar.edu.uade.pfi.backend.service.RunReviewService;
import ar.edu.uade.pfi.backend.service.StudyRunService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class AiBackendControllerTest {
    private Map<String, Object> aiClientResponses;
    private AiBackendService aiBackendService;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() throws Exception {
        aiClientResponses = new HashMap<>();
        AiServiceOperations aiServiceClient = (AiServiceOperations) Proxy.newProxyInstance(
            AiServiceOperations.class.getClassLoader(),
            new Class<?>[] {AiServiceOperations.class},
            (proxy, method, args) -> aiClientResponses.get(method.getName())
        );
        ReviewStoreService reviewStoreService = new ReviewStoreService(
            Mockito.mock(PostgresReviewStoreService.class),
            new ObjectMapper()
        );
        InMemoryStudyRepository repository = new InMemoryStudyRepository();
        StudyRunService studyRunService = new StudyRunService(repository);
        Study study = studyRunService.createStudy("CASE-123", "created");
        studyRunService.createRunWithId(
            java.util.UUID.randomUUID().toString(),
            study,
            "run-123",
            "trace-123",
            "real_baseline",
            "real_baseline",
            "sagittal_spider",
            "",
            "sha256:sag",
            "",
            "run-sag-123",
            "",
            Map.of("sagittal", Map.of("overlay.png", "/api/ai/assets/run-sag-123/sagittal/overlay.png")),
            Map.of("humanReviewRequired", true, "notClinicalDiagnosis", true),
            List.of(),
            "completed",
            "pending",
            "",
            null,
            ""
        );
        aiBackendService = new AiBackendService(
            aiServiceClient,
            reviewStoreService,
            null,
            null,
            null,
            null,
            null,
            repository,
            null,
            null,
            new RunReviewService(repository)
        );
        AiBackendController controller = new AiBackendController(aiBackendService);

        mockMvc = MockMvcBuilders.standaloneSetup(controller).build();
    }

    @Test
    void healthReturnsBackendStatusAndHumanReviewFlag() throws Exception {
        aiClientResponses.put("health", Map.of("status", "ok"));

        mockMvc.perform(get("/api/ai/health"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.backendStatus").value("up"))
            .andExpect(jsonPath("$.aiModuleAvailable").value(true))
            .andExpect(jsonPath("$.humanReviewRequired").value(true))
            .andExpect(jsonPath("$.notClinicalDiagnosis").value(true));
    }

    @Test
    void runPipelineNormalizesSnakeCaseAndKeepsHumanReviewRequiredTrue() throws Exception {
        aiClientResponses.put("runPipeline", Map.of(
            "run_id", "run-123",
            "case_id", "case-001",
            "model_key", "baseline",
            "overlay_path", "outputs/run-123/overlay.png",
            "human_review_required", false,
            "agent_decision", Map.of("technical_label", "review-required")
        ));

        mockMvc.perform(post("/api/ai/pipeline/run")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {
                      "caseId": "case-001",
                      "plane": "sagittal",
                      "modelKey": "baseline",
                      "inputPath": "studies/case-001"
                    }
                    """))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.runId").value("run-123"))
            .andExpect(jsonPath("$.run_id").doesNotExist())
            .andExpect(jsonPath("$.caseId").value("case-001"))
            .andExpect(jsonPath("$.modelKey").value("baseline"))
            .andExpect(jsonPath("$.overlayPath").value("outputs/run-123/overlay.png"))
            .andExpect(jsonPath("$.agentDecision.technicalLabel").value("review-required"))
            .andExpect(jsonPath("$.humanReviewRequired").value(true))
            .andExpect(jsonPath("$.notClinicalDiagnosis").value(true))
            .andExpect(jsonPath("$.review.status").value("pendiente"));
    }

    @Test
    void patchReviewUpdatesLocalReview() throws Exception {
        mockMvc.perform(patch("/api/ai/review/run-123")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {
                      "status": "aceptado",
                      "notes": "Validado por profesional",
                      "reviewer": "dr-demo"
                    }
                    """))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.runId").value("run-123"))
            .andExpect(jsonPath("$.status").value("aceptado"))
            .andExpect(jsonPath("$.notes").value("Validado por profesional"))
            .andExpect(jsonPath("$.reviewer").value("dr-demo"));
    }

    @Test
    void patchReviewRejectsInvalidStatus() throws Exception {
        mockMvc.perform(patch("/api/ai/review/run-123")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {
                      "status": "cerrado",
                      "notes": "Estado no permitido",
                      "reviewer": "dr-demo"
                    }
                    """))
            .andExpect(status().isBadRequest());
    }

    @Test
    void responseNormalizerConvertsNestedSnakeCase() throws Exception {
        Class<?> normalizerType = Class.forName("ar.edu.uade.pfi.backend.util.ResponseNormalizer");
        Method normalizeMap = normalizerType.getMethod("normalizeMap", Map.class);

        @SuppressWarnings("unchecked")
        Map<String, Object> normalized = (Map<String, Object>) normalizeMap.invoke(null, Map.of(
            "run_id", "run-123",
            "agent_decision", Map.of("technical_label", "review-required")
        ));

        assertEquals("run-123", normalized.get("runId"));
        assertFalse(normalized.containsKey("run_id"));
        assertTrue(normalized.containsKey("agentDecision"));

        @SuppressWarnings("unchecked")
        Map<String, Object> agentDecision = (Map<String, Object>) normalized.get("agentDecision");
        assertEquals("review-required", agentDecision.get("technicalLabel"));
    }
}
