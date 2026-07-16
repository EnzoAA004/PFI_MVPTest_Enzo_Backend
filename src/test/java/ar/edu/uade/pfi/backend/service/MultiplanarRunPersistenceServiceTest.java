package ar.edu.uade.pfi.backend.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ar.edu.uade.pfi.backend.domain.Study;
import ar.edu.uade.pfi.backend.domain.StudyRun;
import ar.edu.uade.pfi.backend.dto.MultiplanarRunRequestDto;
import ar.edu.uade.pfi.backend.dto.MultiplanarRunResponseDto;
import ar.edu.uade.pfi.backend.repository.PostgresStudyRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
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
}
