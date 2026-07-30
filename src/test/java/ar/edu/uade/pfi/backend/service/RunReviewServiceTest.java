package ar.edu.uade.pfi.backend.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ar.edu.uade.pfi.backend.domain.Study;
import ar.edu.uade.pfi.backend.dto.MeasurementCorrectionDto;
import ar.edu.uade.pfi.backend.dto.RunReviewRequestDto;
import ar.edu.uade.pfi.backend.repository.InMemoryStudyRepository;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

class RunReviewServiceTest {
    private InMemoryStudyRepository repository;
    private RunReviewService service;
    private StudyRunService studyRunService;

    @BeforeEach
    void setUp() {
        repository = new InMemoryStudyRepository();
        service = new RunReviewService(repository);
        studyRunService = new StudyRunService(repository);
    }

    @Test
    void pendingDraftCanPersistCommentsCorrectionsAndNoFinalReviewedAt() {
        seedRun("multi-pending");

        var response = service.saveReview("multi-pending", new RunReviewRequestDto(
            "pending",
            "",
            "Borrador con observaciones iniciales.",
            List.of(correction())
        ));

        assertEquals("pending", response.reviewStatus());
        assertEquals("Borrador con observaciones iniciales.", response.comments());
        assertNull(response.reviewedAt());
        assertEquals(1, response.corrections().size());
        assertEquals(1, repository.findAuditEventsByEntityId("multi-pending").size());
    }

    @Test
    void aliasesAreNormalizedAndCorrectionsKeepBeforeAndAfterValues() {
        seedRun("multi-alias");

        var response = service.saveReview("multi-alias", new RunReviewRequestDto(
            "descartado",
            "dra-demo",
            "Descartado por inconsistencia visible.",
            List.of(correction())
        ));

        assertEquals("rejected", response.reviewStatus());
        assertEquals(82.4, response.corrections().get(0).beforeValue().get("aiValue"));
        assertEquals(85.1, response.corrections().get(0).afterValue().get("reviewerValue"));
        assertEquals("sagittal", response.corrections().get(0).beforeValue().get("plane"));
        assertEquals(8, response.corrections().get(0).afterValue().get("sliceIndex"));
    }

    @Test
    void rejectedWithoutCommentAndUnknownRunReturnSemanticErrors() {
        seedRun("multi-validation");

        RunReviewException rejectedWithoutComment = assertThrows(RunReviewException.class, () -> service.saveReview(
            "multi-validation",
            new RunReviewRequestDto("rejected", "dra-demo", "", List.of())
        ));
        assertEquals(HttpStatus.BAD_REQUEST, rejectedWithoutComment.status());
        assertEquals("REVIEW_COMMENT_REQUIRED", rejectedWithoutComment.code());

        RunReviewException unknownRun = assertThrows(RunReviewException.class, () -> service.saveReview(
            "multi-missing",
            new RunReviewRequestDto("accepted", "dra-demo", "", List.of())
        ));
        assertEquals(HttpStatus.NOT_FOUND, unknownRun.status());
        assertEquals("RUN_NOT_FOUND", unknownRun.code());
    }

    @Test
    void acceptedIsVisibleAsAcceptedAndLeavesPendingCountInWorklist() {
        Study study = seedRun("multi-worklist");

        service.saveReview("multi-worklist", new RunReviewRequestDto("accepted", "dra-demo", "", List.of()));
        var worklist = new StudyWorklistService(repository, false).listStudies();

        assertEquals(1, worklist.items().size());
        assertEquals("aceptado", worklist.items().get(0).reviewStatus());
        assertEquals(0L, worklist.summary().get("pending"));
        assertEquals(1L, worklist.summary().get("completed"));
        assertTrue(repository.findRunsByStudyId(study.id()).stream().allMatch(run -> "accepted".equals(run.reviewStatus())));
    }

    @Test
    void rejectsCorrectionForUnknownMeasurementOrWrongSlice() {
        seedRun("multi-slice-validation");

        RunReviewException unknownMeasurement = assertThrows(RunReviewException.class, () -> service.saveReview(
            "multi-slice-validation",
            new RunReviewRequestDto(
                "observed",
                "dra-demo",
                "Referencia inconsistente.",
                List.of(new MeasurementCorrectionDto(
                    "missing-measurement",
                    "Missing",
                    Map.of("plane", "sagittal", "sliceIndex", 8),
                    Map.of("plane", "sagittal", "sliceIndex", 8, "reviewerValue", 1),
                    "No existe."
                ))
            )
        ));
        assertEquals(HttpStatus.BAD_REQUEST, unknownMeasurement.status());
        assertEquals("INVALID_MEASUREMENT_CORRECTION", unknownMeasurement.code());

        RunReviewException wrongSlice = assertThrows(RunReviewException.class, () -> service.saveReview(
            "multi-slice-validation",
            new RunReviewRequestDto(
                "observed",
                "dra-demo",
                "Slice inconsistente.",
                List.of(new MeasurementCorrectionDto(
                    "canalAreaMm2",
                    "Area del canal",
                    Map.of("plane", "sagittal", "sliceIndex", 7),
                    Map.of("plane", "sagittal", "sliceIndex", 7, "reviewerValue", 85.1),
                    "No corresponde al slice."
                ))
            )
        ));
        assertEquals(HttpStatus.BAD_REQUEST, wrongSlice.status());
        assertEquals("INVALID_MEASUREMENT_CORRECTION", wrongSlice.code());
    }

    @Test
    @SuppressWarnings("unchecked")
    void worklistReopenPublishesProfessionalCorrectionOnSelectedSlice() {
        Study study = seedRun("multi-reopen-correction");

        service.saveReview("multi-reopen-correction", new RunReviewRequestDto(
            "edited",
            "dra-demo",
            "Correccion persistida por slice.",
            List.of(correction())
        ));

        var detail = new StudyWorklistService(repository, false).getStudy(study.caseId());
        Map<String, Object> canonicalRun = detail.runs().get(0).canonicalRun();
        Map<String, Object> planes = (Map<String, Object>) canonicalRun.get("planes");
        Map<String, Object> sagittal = (Map<String, Object>) planes.get("sagittal");
        Map<String, Object> input = (Map<String, Object>) sagittal.get("input");
        List<Map<String, Object>> slices = (List<Map<String, Object>>) input.get("slices");
        Map<String, Object> selected = slices.get(8);

        assertEquals(true, selected.get("hasResults"));
        assertEquals(List.of("canalAreaMm2"), selected.get("measurementIds"));
        assertEquals(1, selected.get("correctionCount"));
        List<Map<String, Object>> corrections = (List<Map<String, Object>>) selected.get("corrections");
        assertEquals("canalAreaMm2", corrections.get(0).get("measurementId"));
        assertEquals(85.1, ((Map<String, Object>) corrections.get(0).get("afterValue")).get("reviewerValue"));
        assertTrue(slices.stream()
            .filter(slice -> !slice.get("index").equals(8))
            .allMatch(slice -> ((List<?>) slice.get("measurementIds")).isEmpty() && !slice.containsKey("corrections")));
        String json = assertDoesNotThrow(() -> new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(canonicalRun));
        assertTrue(!json.contains("relativePath") && !json.contains("/tmp/") && !json.contains("sourcePath"));
    }

    private Study seedRun(String multiplanarRunId) {
        Study study = studyRunService.createStudy("CASE-" + multiplanarRunId, "created");
        Instant now = Instant.now();
        studyRunService.createRunWithId(
            java.util.UUID.randomUUID().toString(),
            study,
            multiplanarRunId,
            "trace-" + multiplanarRunId,
            "real_baseline",
            "real_baseline",
            "sagittal_spider",
            "",
            "sha256:sag",
            "",
            "run-sag-" + multiplanarRunId,
            "",
            Map.of("workspace", "workspace.json"),
            metricsSnapshot("run-sag-" + multiplanarRunId),
            List.of(),
            "completed",
            "pending",
            "",
            null,
            ""
        );
        return study;
    }

    private MeasurementCorrectionDto correction() {
        return new MeasurementCorrectionDto(
            "canalAreaMm2",
            "Area del canal",
            Map.of("aiValue", 82.4, "unit", "mm2"),
            Map.of("reviewerValue", 85.1, "unit", "mm2"),
            "Ajuste profesional."
        );
    }

    private Map<String, Object> metricsSnapshot(String planeRunId) {
        return Map.of(
            "schemaVersion", "pfi.backend-run-snapshot.v2",
            "governance", Map.of("humanReviewRequired", true, "notClinicalDiagnosis", true),
            "planes", Map.of("sagittal", Map.of(
                "planeRunId", planeRunId,
                "plane", "sagittal",
                "input", volumeInput(),
                "landmarks", List.of(Map.of("id", "lm-canal-center")),
                "measurements", List.of(Map.of(
                    "id", "canalAreaMm2",
                    "labelKey", "Area del canal",
                    "aiValue", 82.4,
                    "value", 82.4,
                    "unit", "mm2",
                    "plane", "sagittal",
                    "linkedLandmarkIds", List.of("lm-canal-center")
                ))
            ))
        );
    }

    private Map<String, Object> volumeInput() {
        java.util.List<Map<String, Object>> slices = new java.util.ArrayList<>();
        for (int index = 0; index < 17; index++) {
            Map<String, Object> slice = new java.util.LinkedHashMap<>();
            slice.put("index", index);
            slice.put("displayIndex", index + 1);
            slice.put("previewAsset", Map.of(
                "assetName", "slice-%03d.png".formatted(index),
                "role", "slice-preview",
                "contentType", "image/png",
                "generated", true
            ));
            slice.put("hasResults", index == 8);
            if (index == 8) {
                slice.put("overlayAsset", Map.of(
                    "assetName", "slice-008-overlay.png",
                    "role", "slice-overlay",
                    "contentType", "image/png",
                    "generated", true
                ));
                slice.put("measurementIds", List.of("canalAreaMm2"));
                slice.put("landmarkIds", List.of("lm-canal-center"));
            } else {
                slice.put("measurementIds", List.of());
                slice.put("landmarkIds", List.of());
            }
            slices.add(slice);
        }
        return Map.of(
            "inputId", "input-sag-review",
            "sliceCount", 17,
            "selectedSliceIndex", 8,
            "slices", slices
        );
    }
}
