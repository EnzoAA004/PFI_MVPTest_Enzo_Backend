package ar.edu.uade.pfi.backend.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ar.edu.uade.pfi.backend.domain.DomainAuditEvent;
import ar.edu.uade.pfi.backend.domain.InputResource;
import ar.edu.uade.pfi.backend.domain.MeasurementCorrection;
import ar.edu.uade.pfi.backend.domain.RunArtifact;
import ar.edu.uade.pfi.backend.domain.RunReview;
import ar.edu.uade.pfi.backend.domain.Study;
import ar.edu.uade.pfi.backend.domain.StudyRun;
import ar.edu.uade.pfi.backend.dto.StudyDetailResponseDto;
import ar.edu.uade.pfi.backend.dto.StudyListResponseDto;
import ar.edu.uade.pfi.backend.dto.StudyRunDetailDto;
import ar.edu.uade.pfi.backend.repository.PostgresStudyRepository;
import ar.edu.uade.pfi.backend.repository.StudyRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.DriverManager;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers
class StudyWorklistServicePostgresTest {
    @Container
    static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine")
        .withDatabaseName("pfi_p8a")
        .withUsername("pfi")
        .withPassword("pfi");

    private PostgresStudyRepository repository;
    private StudyWorklistService service;

    @BeforeEach
    void setUp() throws Exception {
        repository = new PostgresStudyRepository(
            new ObjectMapper(),
            postgres.getJdbcUrl() + "&user=" + postgres.getUsername() + "&password=" + postgres.getPassword(),
            true
        );
        truncateDomainTables();
        service = new StudyWorklistService(repository, false);
    }

    @Test
    void emptyDatabaseReturnsEmptyItemsWithoutDemoFallback() throws Exception {
        StudyListResponseDto response = service.listStudies();
        String json = new ObjectMapper().writeValueAsString(response);

        assertEquals("postgres-domain", response.source());
        assertEquals("database", response.dataOrigin());
        assertTrue(response.items().isEmpty());
        assertEquals(0, response.summary().get("total"));
        assertFalse(json.contains("CASE-DEMO-0142"));
        assertFalse(json.contains("backend-demo-worklist"));
    }

    @Test
    void worklistDetailAndRunsComeFromPersistedDomainTables() {
        Instant older = Instant.parse("2026-07-20T10:00:00Z");
        Instant newer = Instant.parse("2026-07-21T10:00:00Z");
        Study study = repository.saveStudy(new Study(
            UUID.randomUUID().toString(),
            "CASE-P8A-REAL",
            "ready",
            "SUBJ-P8A",
            LocalDate.parse("2026-07-20"),
            "MRI",
            "Lumbar Spine",
            "high",
            older,
            newer
        ));
        repository.saveInput(new InputResource(UUID.randomUUID().toString(), study.id(), "sagittal", "input-sag-p8a", "mha", 2048, older));
        repository.saveInput(new InputResource(UUID.randomUUID().toString(), study.id(), "axial", "input-ax-p8a", "mha", 4096, older));

        StudyRun olderRun = repository.saveRun(studyRun(
            UUID.randomUUID().toString(),
            study.id(),
            "multi-p8a-old",
            "trace-p8a-old",
            "run-sag-old",
            "",
            older,
            List.of(new RunArtifact(UUID.randomUUID().toString(), UUID.randomUUID().toString(), "run-sag-old", "sagittal", "overlay.png", "image/png", "overlay.png", older))
        ));
        StudyRun latestRun = repository.saveRun(studyRun(
            UUID.randomUUID().toString(),
            study.id(),
            "multi-p8a-latest",
            "trace-p8a-latest",
            "run-sag-latest",
            "run-ax-latest",
            newer,
            List.of(
                new RunArtifact(UUID.randomUUID().toString(), UUID.randomUUID().toString(), "run-sag-latest", "sagittal", "overlay.png", "image/png", "overlay.png", newer),
                new RunArtifact(UUID.randomUUID().toString(), UUID.randomUUID().toString(), "run-ax-latest", "axial", "mask-preview.png", "image/png", "mask-preview.png", newer)
            )
        ));
        RunReview review = repository.saveReview(
            "multi-p8a-latest",
            "accepted",
            "dra-p8a",
            newer,
            "Validado",
            List.of(new MeasurementCorrection(
                UUID.randomUUID().toString(),
                latestRun.id(),
                "disc-height-l45",
                "Disc Height L4-L5",
                Map.of("aiValue", 13.8, "unit", "mm"),
                Map.of("reviewerValue", 14.1, "unit", "mm"),
                "Ajuste menor",
                newer
            ))
        );
        repository.saveAuditEvent(new DomainAuditEvent(
            UUID.randomUUID().toString(),
            "dra-p8a",
            "run.completed",
            "multi-p8a-latest",
            "trace-p8a-latest",
            newer,
            Map.of("safe", true)
        ));

        StudyListResponseDto list = service.listStudies();
        StudyDetailResponseDto detail = service.getStudy("CASE-P8A-REAL");

        assertEquals(1, list.items().size());
        assertEquals("multi-p8a-latest", list.items().get(0).latestRunId());
        assertEquals("multi-p8a-latest", list.items().get(0).runId());
        assertEquals("sagittal", list.items().get(0).plane());
        assertEquals("aceptado", list.items().get(0).reviewStatus());
        assertEquals("alta", list.items().get(0).priority());
        assertEquals("database", list.items().get(0).dataOrigin());

        assertEquals(2, detail.inputs().size());
        assertEquals(2, detail.runs().size());
        assertEquals("multi-p8a-latest", detail.runs().get(0).summary().runId());
        assertEquals("multi-p8a-old", detail.runs().get(1).summary().runId());
        assertEquals(review.reviewer(), detail.runs().get(0).summary().reviewer());
        assertEquals("aceptado", detail.runs().get(0).summary().reviewStatus());
        assertEquals(2, detail.runs().get(0).summary().measurementCount());
        assertEquals(2, detail.runs().get(0).summary().artifactCount());
        assertTrue(detail.runs().get(0).summary().planes().containsAll(List.of("sagittal", "axial")));
        assertEquals(1, detail.runs().get(1).summary().planes().size());
        assertNull(detail.runs().get(1).summary().axialRunId());
        assertEquals(1, detail.auditTrail().size());

        StudyRunDetailDto latestDetail = detail.runs().get(0);
        Map<String, Object> sagittalMeasurement = latestDetail.measurementsByPlane().get("sagittal").get(0);
        assertEquals(13.8, sagittalMeasurement.get("aiValue"));
        assertEquals("AI", sagittalMeasurement.get("source"));
        assertEquals("disc-height-l45", sagittalMeasurement.get("id"));
        assertEquals(1, latestDetail.corrections().size());
        assertEquals(14.1, latestDetail.corrections().get(0).afterValue().get("reviewerValue"));
        assertTrue(latestDetail.artifactsByPlane().get("sagittal").get(0).get("proxyUrl").toString()
            .startsWith("/api/ai/assets/run-sag-latest/sagittal/overlay.png"));

        Map<String, Object> canonicalRun = latestDetail.canonicalRun();
        assertEquals("multi-p8a-latest", canonicalRun.get("runId"));
        assertEquals("trace-p8a-latest", canonicalRun.get("traceId"));
        assertEquals("CASE-P8A-REAL", canonicalRun.get("caseId"));
        assertEquals(false, canonicalRun.get("synthetic"));
        assertNull(canonicalRun.get("fallbackReason"));
        Map<String, Object> canonicalThreeD = (Map<String, Object>) canonicalRun.get("threeD");
        List<Map<String, Object>> publicThreeDAssets = (List<Map<String, Object>>) canonicalThreeD.get("assets");
        assertEquals("/api/ai/assets/multi-p8a-latest/workspace/lumbar-3d-mesh.json", publicThreeDAssets.get(0).get("url"));
        assertFalse(publicThreeDAssets.get(0).containsKey("relativePath"));
        String canonicalJson = assertDoesNotThrow(() -> new ObjectMapper().writeValueAsString(canonicalRun));
        assertFalse(canonicalJson.contains("/outputs/"));
    }

    /**
     * P9-B.2.1 gap: a legacy row persisted before governance was captured in the
     * snapshot (metricsSnapshot with no "governance" key at all) must never make
     * canonicalRun report humanReviewRequired/notClinicalDiagnosis=true as a
     * fabricated positive default — that would misrepresent an unknown value as
     * a confirmed safety guarantee.
     */
    @Test
    @SuppressWarnings("unchecked")
    void legacySnapshotWithoutGovernanceNeverFabricatesHumanReviewRequiredTrue() throws Exception {
        Instant createdAt = Instant.parse("2026-01-05T10:00:00Z");
        Study study = repository.saveStudy(new Study(
            UUID.randomUUID().toString(), "CASE-LEGACY-NO-GOVERNANCE", "ready", null,
            null, "MRI", null, "medium", createdAt, createdAt
        ));
        Map<String, Object> legacySnapshotWithoutGovernance = Map.of(
            "schemaVersion", "pfi.backend-run-snapshot.v1",
            "planes", Map.of("sagittal", Map.of("measurements", List.of()))
        );
        repository.saveRun(new StudyRun(
            UUID.randomUUID().toString(), study.id(), "multi-legacy-no-gov", "trace-legacy-no-gov",
            "real_baseline", "real_baseline", "sagittal_spider", "", "sha256:sag", "",
            "run-sag-legacy", "", Map.of(), legacySnapshotWithoutGovernance,
            List.of(new RunArtifact(UUID.randomUUID().toString(), UUID.randomUUID().toString(), "run-sag-legacy", "sagittal", "overlay.png", "image/png", "overlay.png", createdAt)),
            "completed", "pending", "", null, "", createdAt, createdAt
        ));

        StudyDetailResponseDto detail = service.getStudy("CASE-LEGACY-NO-GOVERNANCE");
        Map<String, Object> canonicalRun = detail.runs().get(0).canonicalRun();

        assertNull(canonicalRun.get("humanReviewRequired"));
        assertNull(canonicalRun.get("notClinicalDiagnosis"));
        Map<String, Object> governance = (Map<String, Object>) canonicalRun.get("governance");
        assertTrue(governance.isEmpty());
    }

    @Test
    void getStudyForUnknownCaseReturnsStudyNotFound() {
        assertThrows(StudyNotFoundException.class, () -> service.getStudy("CASE-NOT-FOUND"));
    }

    @Test
    void databaseFailureBecomesDatabaseUnavailableWithoutDemoFallback() {
        StudyWorklistService broken = new StudyWorklistService(new BrokenStudyRepository(), false);
        assertThrows(DatabaseUnavailableException.class, broken::listStudies);
    }

    private StudyRun studyRun(
        String id,
        String studyId,
        String multiplanarRunId,
        String traceId,
        String sagittalRunId,
        String axialRunId,
        Instant createdAt,
        List<RunArtifact> artifacts
    ) {
        return new StudyRun(
            id,
            studyId,
            multiplanarRunId,
            traceId,
            "real_baseline",
            "real_baseline",
            "sagittal_spider",
            axialRunId.isBlank() ? "" : "axial_t2_alkafri",
            "sha256:sag",
            axialRunId.isBlank() ? "" : "sha256:ax",
            sagittalRunId,
            axialRunId,
            Map.of("workspace", "workspace.json"),
            metricsSnapshot(multiplanarRunId, sagittalRunId, axialRunId),
            artifacts,
            "completed",
            "pending",
            "",
            null,
            "",
            createdAt,
            createdAt
        );
    }

    private Map<String, Object> metricsSnapshot(String multiplanarRunId, String sagittalRunId, String axialRunId) {
        return Map.of(
            "schemaVersion", "pfi.backend-run-snapshot.v2",
            "sourceSchemaVersion", "pfi.multiplanar-run.v2",
            "status", "completed",
            "requestedPlanes", axialRunId.isBlank() ? List.of("sagittal") : List.of("sagittal", "axial"),
            "completedPlanes", axialRunId.isBlank() ? List.of("sagittal") : List.of("sagittal", "axial"),
            "synthetic", false,
            "governance", Map.of("humanReviewRequired", true, "notClinicalDiagnosis", true),
            "threeD", Map.of(
                "enabled", !axialRunId.isBlank(),
                "status", axialRunId.isBlank() ? "blocked_missing_axial" : "experimental_ready",
                "assets", axialRunId.isBlank() ? List.of() : List.of(Map.of(
                    "assetName", "lumbar-3d-mesh.json",
                    "role", "mesh_3d",
                    "contentType", "application/json",
                    "generated", true,
                    "relativePath", "/outputs/multiplanar_3d/" + multiplanarRunId + "/lumbar-3d-mesh.json"
                )),
                "reconstruction", Map.of(
                    "kind", "experimental_geometric_proxy",
                    "method", "dual_plane_bbox_proxy",
                    "anatomicalReconstruction", false,
                    "volumetricReconstruction", false,
                    "coordinateSystem", "local_proxy_space",
                    "units", "normalized"
                )
            ),
            "planes", Map.of(
                "sagittal", Map.of("measurements", List.of(Map.of(
                    "id", "disc-height-l45",
                    "labelKey", "Disc Height L4-L5",
                    "value", 13.8,
                    "unit", "mm",
                    "confidence", 0.82,
                    "linkedLandmarkIds", List.of("lm-l4", "lm-l5")
                ))),
                "axial", Map.of("measurements", axialRunId.isBlank() ? List.of() : List.of(Map.of(
                    "id", "canal-diameter-l45",
                    "labelKey", "Central Canal Diameter",
                    "value", 14.2,
                    "unit", "mm",
                    "confidence", 0.76
                )))
            )
        );
    }

    private void truncateDomainTables() throws Exception {
        try (var connection = DriverManager.getConnection(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
             var statement = connection.createStatement()) {
            statement.execute("""
                TRUNCATE domain_audit_events, domain_review_corrections, domain_run_artifacts,
                         domain_study_runs, domain_input_resources, domain_studies
                RESTART IDENTITY CASCADE
                """);
        }
    }

    private static class BrokenStudyRepository implements StudyRepository {
        @Override public List<Study> findAllStudies() { throw new IllegalStateException("postgres down"); }
        @Override public Study saveStudy(Study study) { throw new UnsupportedOperationException(); }
        @Override public InputResource saveInput(InputResource input) { throw new UnsupportedOperationException(); }
        @Override public StudyRun saveRun(StudyRun run) { throw new UnsupportedOperationException(); }
        @Override public Optional<Study> findStudyByCaseId(String caseId) { throw new UnsupportedOperationException(); }
        @Override public List<Study> findStudiesBySubjectRef(String subjectRef) { throw new IllegalStateException("postgres down"); }
        @Override public List<InputResource> findInputsByStudyId(String studyId) { throw new UnsupportedOperationException(); }
        @Override public List<StudyRun> findRunsByStudyId(String studyId) { throw new UnsupportedOperationException(); }
        @Override public Optional<StudyRun> findLatestRunByStudyId(String studyId) { throw new UnsupportedOperationException(); }
        @Override public Optional<StudyRun> findRunByMultiplanarRunId(String multiplanarRunId) { throw new UnsupportedOperationException(); }
        @Override public Optional<StudyRun> findRunByTraceId(String traceId) { throw new UnsupportedOperationException(); }
        @Override public List<RunArtifact> findArtifactsByRunId(String studyRunId) { throw new UnsupportedOperationException(); }
        @Override public Optional<RunArtifact> findArtifactByRunPlaneAndName(String runId, String plane, String assetName) { throw new UnsupportedOperationException(); }
        @Override public RunArtifact updateArtifactStorage(String artifactId, String storageStatus, String storageKind, Long sizeBytes, String sha256) { throw new UnsupportedOperationException(); }
        @Override public StudyRun updateRunMetricsSnapshot(String multiplanarRunId, Map<String, Object> metricsSnapshot) { throw new UnsupportedOperationException(); }
        @Override public RunReview saveReview(String multiplanarRunId, String reviewStatus, String reviewer, Instant reviewedAt, String comments, List<MeasurementCorrection> corrections) { throw new UnsupportedOperationException(); }
        @Override public RunReview saveReview(String multiplanarRunId, String reviewStatus, String reviewer, Instant reviewedAt, String comments, List<MeasurementCorrection> corrections, DomainAuditEvent auditEvent) { throw new UnsupportedOperationException(); }
        @Override public Optional<RunReview> findReviewByMultiplanarRunId(String multiplanarRunId) { throw new UnsupportedOperationException(); }
        @Override public List<MeasurementCorrection> findCorrectionsByStudyRunId(String studyRunId) { throw new UnsupportedOperationException(); }
        @Override public DomainAuditEvent saveAuditEvent(DomainAuditEvent event) { throw new UnsupportedOperationException(); }
        @Override public List<DomainAuditEvent> findAuditEventsByTraceId(String traceId) { throw new UnsupportedOperationException(); }
        @Override public List<DomainAuditEvent> findAuditEventsByEntityId(String entityId) { throw new UnsupportedOperationException(); }
        @Override public List<DomainAuditEvent> findAuditEventsByStudyId(String studyId) { throw new UnsupportedOperationException(); }
    }
}
