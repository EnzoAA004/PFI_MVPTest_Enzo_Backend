package ar.edu.uade.pfi.backend.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import ar.edu.uade.pfi.backend.client.AiServiceOperations;
import ar.edu.uade.pfi.backend.config.ApiExceptionHandler;
import ar.edu.uade.pfi.backend.controller.AiMultiplanarController;
import ar.edu.uade.pfi.backend.domain.Study;
import ar.edu.uade.pfi.backend.domain.StudyRun;
import ar.edu.uade.pfi.backend.dto.MultiplanarRunRequestDto;
import ar.edu.uade.pfi.backend.dto.MultiplanarRunResponseDto;
import ar.edu.uade.pfi.backend.repository.PostgresStudyRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers
class MultiplanarRunPersistenceServiceTest {
    @Container
    static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine")
        .withDatabaseName("pfi_be006")
        .withUsername("pfi")
        .withPassword("pfi");

    @Test
    void persistsMultiplanarRunResponseAndRecoversItByRunIdAndTraceId() {
        PostgresStudyRepository repository = new PostgresStudyRepository(
            new ObjectMapper(),
            postgres.getJdbcUrl() + "&user=" + postgres.getUsername() + "&password=" + postgres.getPassword(),
            true
        );
        StudyRunService studyRunService = new StudyRunService(repository);
        MultiplanarRunPersistenceService persistence = new MultiplanarRunPersistenceService(studyRunService);

        MultiplanarRunRequestDto request = new MultiplanarRunRequestDto(
            "CASE-BE-006",
            "input-sag-be006",
            "input-ax-be006",
            null,
            null,
            "sagittal_spider",
            "axial_t2_alkafri",
            false,
            Map.of("inferenceMode", "real_baseline", "allowContractFallback", false)
        );
        MultiplanarRunResponseDto response = response();

        persistence.persistSuccessfulRun(request, response);

        Study study = studyRunService.findStudyByCaseId("CASE-BE-006").orElseThrow();
        StudyRun byRunId = studyRunService.findRunByMultiplanarRunId("multi-be006").orElseThrow();
        StudyRun byTraceId = studyRunService.findRunByTraceId("trace-be006").orElseThrow();

        assertEquals(study.id(), byRunId.studyId());
        assertEquals(byRunId.id(), byTraceId.id());
        assertEquals("trace-be006", byRunId.traceId());
        assertEquals("real_baseline", byRunId.requestedInferenceMode());
        assertEquals("real_baseline", byRunId.effectiveInferenceMode());
        assertEquals("sagittal_spider", byRunId.sagittalModelKey());
        assertEquals("axial_t2_alkafri", byRunId.axialModelKey());
        assertEquals("sha256:sag-be006", byRunId.sagittalArtifactHash());
        assertEquals("sha256:ax-be006", byRunId.axialArtifactHash());
        assertEquals("run-sag-be006", byRunId.sagittalRunId());
        assertEquals("run-ax-be006", byRunId.axialRunId());
        assertEquals("pending", byRunId.reviewStatus());
        assertFalse(byRunId.metricsSnapshot().isEmpty());
        assertEquals(2, studyRunService.findInputs(study).size());
        assertTrue(byRunId.artifacts().stream().anyMatch(artifact -> artifact.runId().equals("run-sag-be006") && artifact.assetName().equals("overlay.png")));
        assertTrue(byRunId.artifacts().stream().anyMatch(artifact -> artifact.runId().equals("run-ax-be006") && artifact.assetName().equals("mask-preview.png")));
        assertTrue(byRunId.artifacts().stream().noneMatch(artifact -> artifact.artifactRef().contains("/") || artifact.artifactRef().contains("\\") || artifact.artifactRef().contains("..")));
    }

    @Test
    @SuppressWarnings("unchecked")
    void persistsSagittalOnlyRunWithoutAxialInputRunOrAssets() {
        PostgresStudyRepository repository = new PostgresStudyRepository(
            new ObjectMapper(),
            postgres.getJdbcUrl() + "&user=" + postgres.getUsername() + "&password=" + postgres.getPassword(),
            true
        );
        StudyRunService studyRunService = new StudyRunService(repository);
        MultiplanarRunPersistenceService persistence = new MultiplanarRunPersistenceService(studyRunService);

        MultiplanarRunRequestDto request = new MultiplanarRunRequestDto(
            "CASE-BE-SAG-ONLY",
            "input-sag-only",
            null,
            null,
            null,
            "sagittal_spider",
            "axial_t2_alkafri",
            false,
            Map.of(
                "inferenceMode", "real_baseline",
                "requestedInferenceMode", "real_baseline",
                "allowContractFallback", false,
                "axialMode", "optional_not_provided"
            )
        );
        MultiplanarRunResponseDto response = sagittalOnlyResponse();

        persistence.persistSuccessfulRun(request, response);

        Study study = studyRunService.findStudyByCaseId("CASE-BE-SAG-ONLY").orElseThrow();
        StudyRun byRunId = studyRunService.findRunByMultiplanarRunId("multi-sag-only").orElseThrow();
        StudyRun byTraceId = studyRunService.findRunByTraceId("trace-sag-only").orElseThrow();

        assertEquals(study.id(), byRunId.studyId());
        assertEquals(byRunId.id(), byTraceId.id());
        assertEquals("trace-sag-only", byRunId.traceId());
        assertEquals("real_baseline", byRunId.requestedInferenceMode());
        assertEquals("mixed", byRunId.effectiveInferenceMode());
        assertEquals("sagittal_spider", byRunId.sagittalModelKey());
        assertEquals("axial_t2_alkafri", byRunId.axialModelKey());
        assertEquals(MultiplanarRealBaselineContractValidator.SAGITTAL_ARTIFACT_HASH, byRunId.sagittalArtifactHash());
        assertEquals("", byRunId.axialArtifactHash());
        assertEquals("run-sag-only", byRunId.sagittalRunId());
        assertEquals("", byRunId.axialRunId());
        assertEquals(1, studyRunService.findInputs(study).size());
        assertTrue(byRunId.artifacts().stream().allMatch(artifact -> artifact.plane().equals("sagittal")));
        assertTrue(byRunId.artifacts().stream().anyMatch(artifact -> artifact.assetName().equals("overlay.png")));

        Map<String, Object> sagittalMetrics = (Map<String, Object>) byRunId.metricsSnapshot().get("sagittal");
        assertEquals("input-sag-only", sagittalMetrics.get("inputId"));
        assertEquals("sagittal-spider-final-v1", sagittalMetrics.get("modelVersion"));
        assertEquals(MultiplanarRealBaselineContractValidator.SAGITTAL_ARTIFACT_HASH, sagittalMetrics.get("artifactHash"));
        assertEquals(9, ((Number) sagittalMetrics.get("selectedSlice")).intValue());
        assertEquals(2, ((Number) sagittalMetrics.get("selectedAxis")).intValue());
        assertEquals(17, ((Number) sagittalMetrics.get("sliceCount")).intValue());
        assertEquals("move_axis_0_to_last", sagittalMetrics.get("inputOrientationTransform"));
        assertEquals(true, sagittalMetrics.get("humanReviewRequired"));
        assertEquals(true, sagittalMetrics.get("notClinicalDiagnosis"));
    }

    @Test
    void twoConsecutiveMultiplanarPostRequestsRemainIdempotent() throws Exception {
        PostgresStudyRepository repository = new PostgresStudyRepository(
            new ObjectMapper(),
            postgres.getJdbcUrl() + "&user=" + postgres.getUsername() + "&password=" + postgres.getPassword(),
            true
        );
        StudyRunService studyRunService = new StudyRunService(repository);
        MultiplanarRunPersistenceService persistence = new MultiplanarRunPersistenceService(studyRunService);
        AiServiceOperations ai = org.mockito.Mockito.mock(AiServiceOperations.class);
        when(ai.runMultiplanar(any())).thenReturn(sagittalOnlyResponse(), sagittalOnlyResponse());
        MockMvc mockMvc = MockMvcBuilders
            .standaloneSetup(new AiMultiplanarController(ai, persistence, null))
            .setControllerAdvice(new ApiExceptionHandler())
            .build();

        String body = """
            {
              "caseId": "CASE-BE-SAG-ONLY",
              "sagittalInputId": "input-sag-only",
              "allowContractFallback": false,
              "metadata": {
                "inferenceMode": "real_baseline",
                "axialMode": "optional_not_provided"
              }
            }
            """;

        mockMvc.perform(post("/api/ai/multiplanar/run").contentType(MediaType.APPLICATION_JSON).content(body))
            .andExpect(status().isOk());
        mockMvc.perform(post("/api/ai/multiplanar/run").contentType(MediaType.APPLICATION_JSON).content(body))
            .andExpect(status().isOk());

        StudyRun recovered = studyRunService.findRunByMultiplanarRunId("multi-sag-only").orElseThrow();
        assertEquals("trace-sag-only", recovered.traceId());
        assertEquals("run-sag-only", recovered.sagittalRunId());
        assertTrue(recovered.artifacts().stream().allMatch(artifact -> artifact.studyRunId().equals(recovered.id())));
    }

    private MultiplanarRunResponseDto response() {
        return new MultiplanarRunResponseDto(
            "multi-be006",
            "trace-be006",
            "real_baseline",
            new MultiplanarRunResponseDto.PlanesDto(
                new MultiplanarRunResponseDto.PlaneDto(
                    "run-sag-be006",
                    "sagittal",
                    "sagittal_spider",
                    "completed",
                    "real_baseline",
                    Map.of("artifactHash", "sha256:sag-be006"),
                    List.of(Map.of("label", "canal_lumbar")),
                    List.of(Map.of("name", "L4_left_pedicle")),
                    Map.of("canalAreaMm2", 82.4),
                    Map.of("quality", 0.94),
                    Map.of("overlay", "artifacts/run-sag-be006/overlay.png")
                ),
                new MultiplanarRunResponseDto.PlaneDto(
                    "run-ax-be006",
                    "axial",
                    "axial_t2_alkafri",
                    "completed",
                    "real_baseline",
                    Map.of("artifactHash", "sha256:ax-be006"),
                    List.of(Map.of("label", "estenosis")),
                    List.of(Map.of("name", "canal_center")),
                    Map.of("leftForamenMm", 3.1),
                    Map.of("quality", 0.88),
                    Map.of("maskPreview", "mask-preview.png")
                )
            ),
            Map.of("workspace", "workspace.json"),
            Map.of("status", "pendiente")
        );
    }

    private MultiplanarRunResponseDto sagittalOnlyResponse() {
        return new MultiplanarRunResponseDto(
            "multiplanar_run_ready",
            "multiplanar-run-v1",
            "multi-sag-only",
            "trace-sag-only",
            "CASE-BE-SAG-ONLY",
            "sagittal_only_with_optional_axial",
            "real_baseline",
            "mixed",
            new MultiplanarRunResponseDto.PlanesDto(
                new MultiplanarRunResponseDto.PlaneDto(
                    "run-sag-only",
                    "CASE-BE-SAG-ONLY",
                    "sagittal",
                    "sagittal_spider",
                    "sagittal-spider-final-v1",
                    MultiplanarRealBaselineContractValidator.SAGITTAL_ARTIFACT_HASH,
                    "completed",
                    "real_baseline",
                    "real_baseline",
                    "real_baseline",
                    false,
                    "input-sag-only",
                    Map.of("artifactHash", MultiplanarRealBaselineContractValidator.SAGITTAL_ARTIFACT_HASH),
                    Map.of(
                        "inferenceMode", "real_baseline",
                        "artifactHash", MultiplanarRealBaselineContractValidator.SAGITTAL_ARTIFACT_HASH,
                        "realInferenceAvailable", true
                    ),
                    List.of(Map.of("label", "sagittal")),
                    List.of(),
                    List.of(Map.of("label", "canal_lumbar")),
                    List.of(Map.of("name", "L4_left_pedicle")),
                    Map.of("status", "real_baseline_ready", "canalAreaMm2", 82.4),
                    Map.of("quality", 0.94),
                    Map.of("confidence", 0.94),
                    Map.of("input.png", "input.png", "overlay.png", "overlay.png"),
                    Map.of(
                        "selectedSlice", 9,
                        "selectedAxis", 2,
                        "sliceCount", 17,
                        "inputShapeNative", List.of(17, 512, 512),
                        "inputShapeCanonical", List.of(512, 512, 17),
                        "inputOrientationTransform", "move_axis_0_to_last",
                        "inPlaneSpacing", List.of(0.7, 0.7),
                        "inPlaneSpacingUnit", "mm"
                    ),
                    true,
                    true,
                    false
                ),
                null
            ),
            Map.of("workspace", "workspace.json"),
            null,
            Map.of("sagittalRunReady", true, "axialRunReady", false, "dualRunReady", false),
            Map.of("status", "pending"),
            Map.of("axialMode", "optional_not_provided"),
            true,
            true,
            false
        );
    }
}
