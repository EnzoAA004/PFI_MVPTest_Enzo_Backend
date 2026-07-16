package ar.edu.uade.pfi.backend.repository;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ar.edu.uade.pfi.backend.domain.InputResource;
import ar.edu.uade.pfi.backend.domain.RunArtifact;
import ar.edu.uade.pfi.backend.domain.Study;
import ar.edu.uade.pfi.backend.domain.StudyRun;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.DriverManager;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers
class PostgresStudyRepositoryTest {
    @Container
    static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine")
        .withDatabaseName("pfi_be005b")
        .withUsername("pfi")
        .withPassword("pfi");

    @Test
    void appliesMigrationAndPersistsStudyInputsRunArtifactsMetricsAndReview() throws Exception {
        PostgresStudyRepository repository = new PostgresStudyRepository(
            new ObjectMapper(),
            postgres.getJdbcUrl() + "&user=" + postgres.getUsername() + "&password=" + postgres.getPassword(),
            true
        );

        try (var connection = DriverManager.getConnection(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
             var statement = connection.prepareStatement("SELECT version FROM schema_migrations WHERE version = ?")) {
            statement.setString(1, "V20260716_005_study_input_run_model.sql");
            try (var rs = statement.executeQuery()) {
                assertTrue(rs.next());
            }
        }

        Instant now = Instant.parse("2026-07-16T15:00:00Z");
        Study study = repository.saveStudy(new Study(UUID.randomUUID().toString(), "CASE-DEMO-005B", "ready", now, now));
        InputResource sagittal = repository.saveInput(new InputResource(UUID.randomUUID().toString(), study.id(), "sagittal", "input-sag-005b", "png", 1234, now));
        InputResource axial = repository.saveInput(new InputResource(UUID.randomUUID().toString(), study.id(), "axial", "input-ax-005b", "npy", 4321, now));

        String runId = UUID.randomUUID().toString();
        StudyRun run = repository.saveRun(new StudyRun(
            runId,
            study.id(),
            "multi-005b",
            "trace-005b",
            "real_baseline",
            "real_baseline",
            "sagittal_spider",
            "axial_t2_alkafri",
            "sha256:sag-checkpoint",
            "sha256:ax-checkpoint",
            "run-sag-005b",
            "run-ax-005b",
            Map.of(
                "workspace", "workspace.json",
                "sagittal", Map.of("overlay", "overlay.png"),
                "axial", Map.of("maskPreview", "mask-preview.png")
            ),
            Map.of(
                "quality", Map.of("score", 0.94),
                "confidence", Map.of("sagittal", 0.91, "axial", 0.88)
            ),
            List.of(
                new RunArtifact(UUID.randomUUID().toString(), runId, "run-sag-005b", "sagittal", "overlay.png", "image/png", "overlay.png", now),
                new RunArtifact(UUID.randomUUID().toString(), runId, "run-ax-005b", "axial", "mask-preview.png", "image/png", "mask-preview.png", now)
            ),
            "completed",
            "accepted",
            "dra-demo",
            now,
            "Aprobado para demo academica.",
            now,
            now
        ));

        Study recoveredStudy = repository.findStudyByCaseId("CASE-DEMO-005B").orElseThrow();
        List<InputResource> inputs = repository.findInputsByStudyId(recoveredStudy.id());
        StudyRun byMultiplanarRunId = repository.findRunByMultiplanarRunId("multi-005b").orElseThrow();
        StudyRun byTraceId = repository.findRunByTraceId("trace-005b").orElseThrow();

        assertEquals(study.id(), recoveredStudy.id());
        assertEquals(2, inputs.size());
        assertTrue(inputs.stream().anyMatch(input -> input.id().equals(sagittal.id()) && input.inputId().equals("input-sag-005b")));
        assertTrue(inputs.stream().anyMatch(input -> input.id().equals(axial.id()) && input.inputId().equals("input-ax-005b")));
        assertEquals(run.id(), byMultiplanarRunId.id());
        assertEquals(run.id(), byTraceId.id());
        assertEquals("trace-005b", byMultiplanarRunId.traceId());
        assertEquals("sha256:sag-checkpoint", byMultiplanarRunId.sagittalArtifactHash());
        assertEquals("sha256:ax-checkpoint", byMultiplanarRunId.axialArtifactHash());
        assertEquals("accepted", byMultiplanarRunId.reviewStatus());
        assertEquals("dra-demo", byMultiplanarRunId.reviewer());
        assertEquals("Aprobado para demo academica.", byMultiplanarRunId.comments());
        assertFalse(byMultiplanarRunId.metricsSnapshot().isEmpty());
        assertEquals(2, byMultiplanarRunId.artifacts().size());
        assertTrue(byMultiplanarRunId.artifacts().stream().allMatch(artifact -> artifact.artifactRef().endsWith(".png")));
        assertTrue(byMultiplanarRunId.artifacts().stream().noneMatch(artifact -> artifact.artifactRef().contains("\\") || artifact.artifactRef().contains("/") || artifact.artifactRef().contains("..")));
    }
}
