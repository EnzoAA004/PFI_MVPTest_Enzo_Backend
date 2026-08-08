package ar.edu.uade.pfi.backend.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
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

    var response =
        service.saveReview(
            "multi-pending",
            new RunReviewRequestDto(
                "pending", "", "Borrador con observaciones iniciales.", List.of(correction())));

    assertEquals("pending", response.reviewStatus());
    assertEquals("Borrador con observaciones iniciales.", response.comments());
    assertNull(response.reviewedAt());
    assertEquals(1, response.corrections().size());
    assertEquals(1, repository.findAuditEventsByEntityId("multi-pending").size());
  }

  @Test
  void aliasesAreNormalizedAndCorrectionsKeepBeforeAndAfterValues() {
    seedRun("multi-alias");

    var response =
        service.saveReview(
            "multi-alias",
            new RunReviewRequestDto(
                "descartado",
                "dra-demo",
                "Descartado por inconsistencia visible.",
                List.of(correction())));

    assertEquals("rejected", response.reviewStatus());
    assertEquals(82.4, response.corrections().get(0).beforeValue().get("aiValue"));
    assertEquals(85.1, response.corrections().get(0).afterValue().get("reviewerValue"));
  }

  @Test
  void rejectedWithoutCommentAndUnknownRunReturnSemanticErrors() {
    seedRun("multi-validation");

    RunReviewException rejectedWithoutComment =
        assertThrows(
            RunReviewException.class,
            () ->
                service.saveReview(
                    "multi-validation",
                    new RunReviewRequestDto("rejected", "dra-demo", "", List.of())));
    assertEquals(HttpStatus.BAD_REQUEST, rejectedWithoutComment.status());
    assertEquals("REVIEW_COMMENT_REQUIRED", rejectedWithoutComment.code());

    RunReviewException unknownRun =
        assertThrows(
            RunReviewException.class,
            () ->
                service.saveReview(
                    "multi-missing",
                    new RunReviewRequestDto("accepted", "dra-demo", "", List.of())));
    assertEquals(HttpStatus.NOT_FOUND, unknownRun.status());
    assertEquals("RUN_NOT_FOUND", unknownRun.code());
  }

  @Test
  void acceptedIsVisibleAsAcceptedAndLeavesPendingCountInWorklist() {
    Study study = seedRun("multi-worklist");

    service.saveReview(
        "multi-worklist", new RunReviewRequestDto("accepted", "dra-demo", "", List.of()));
    var worklist = new StudyWorklistService(repository, false).listStudies();

    assertEquals(1, worklist.items().size());
    assertEquals("aceptado", worklist.items().get(0).reviewStatus());
    assertEquals(0L, worklist.summary().get("pending"));
    assertEquals(1L, worklist.summary().get("completed"));
    assertTrue(
        repository.findRunsByStudyId(study.id()).stream()
            .allMatch(run -> "accepted".equals(run.reviewStatus())));
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
        Map.of("humanReviewRequired", true, "notClinicalDiagnosis", true),
        List.of(),
        "completed",
        "pending",
        "",
        null,
        "");
    return study;
  }

  private MeasurementCorrectionDto correction() {
    return new MeasurementCorrectionDto(
        "canalAreaMm2",
        "Area del canal",
        Map.of("aiValue", 82.4, "unit", "mm2"),
        Map.of("reviewerValue", 85.1, "unit", "mm2"),
        "Ajuste profesional.");
  }
}
