package ar.edu.uade.pfi.backend.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import ar.edu.uade.pfi.backend.domain.CanonicalMultiplanarRun;
import ar.edu.uade.pfi.backend.domain.CanonicalPlaneRun;
import ar.edu.uade.pfi.backend.domain.Study;
import ar.edu.uade.pfi.backend.dto.MultiplanarRunRequestDto;
import ar.edu.uade.pfi.backend.dto.StudyMetadataDto;
import ar.edu.uade.pfi.backend.repository.InMemoryStudyRepository;
import ar.edu.uade.pfi.backend.service.exception.StudyMetadataException;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class StudyMetadataServiceTest {
  private final InMemoryStudyRepository repository = new InMemoryStudyRepository();
  private final StudyRunService service = new StudyRunService(repository);

  @Test
  void createsStudyWithSubjectRefAndKeepsNullWhenMetadataMissing() {
    Study withMetadata =
        service.upsertStudyMetadata(
            "CASE-SPIDER-101",
            "created",
            new StudyMetadataDto(
                " SPIDER-101 ",
                LocalDate.parse("2026-07-26"),
                " MRI ",
                " RM lumbar sagital T2 ",
                "medium"));
    assertEquals("SPIDER-101", withMetadata.subjectRef());
    assertEquals(LocalDate.parse("2026-07-26"), withMetadata.studyDate());
    assertEquals("MRI", withMetadata.modality());

    Study withoutMetadata = service.upsertStudyMetadata("CASE-NO-SUBJECT", "created", null);
    assertNull(withoutMetadata.subjectRef());
  }

  @Test
  void existingNullAcceptsAssignmentAndSameSubjectIsIdempotent() {
    Study created = service.upsertStudyMetadata("CASE-ASSIGN", "created", null);
    Study assigned =
        service.upsertStudyMetadata(
            "CASE-ASSIGN", "created", new StudyMetadataDto("SPIDER-101", null, null, null, null));
    Study same =
        service.upsertStudyMetadata(
            "CASE-ASSIGN", "created", new StudyMetadataDto("SPIDER-101", null, null, null, null));

    assertEquals(created.id(), assigned.id());
    assertEquals(assigned.updatedAt(), same.updatedAt());
    assertEquals("SPIDER-101", same.subjectRef());
  }

  @Test
  void differentSubjectRefConflictsAndRequestWithoutMetadataDoesNotClearExistingValues() {
    Study original =
        service.upsertStudyMetadata(
            "CASE-CONFLICT",
            "created",
            new StudyMetadataDto(
                "SPIDER-101", LocalDate.parse("2026-07-26"), "MRI", "RM lumbar", "high"));

    StudyMetadataException conflict =
        assertThrows(
            StudyMetadataException.class,
            () ->
                service.upsertStudyMetadata(
                    "CASE-CONFLICT",
                    "created",
                    new StudyMetadataDto("SPIDER-999", null, null, null, null)));
    assertEquals("SUBJECT_REFERENCE_CONFLICT", conflict.code());

    Study preserved = service.upsertStudyMetadata("CASE-CONFLICT", "created", null);
    assertEquals(original.subjectRef(), preserved.subjectRef());
    assertEquals(original.studyDate(), preserved.studyDate());
    assertEquals(original.description(), preserved.description());
  }

  @Test
  void invalidSubjectRefIsRejected() {
    StudyMetadataException ex =
        assertThrows(
            StudyMetadataException.class,
            () ->
                service.upsertStudyMetadata(
                    "CASE-INVALID",
                    "created",
                    new StudyMetadataDto("SPIDER 101", null, null, null, null)));
    assertEquals("INVALID_SUBJECT_REFERENCE", ex.code());
  }

  @Test
  void updatingMetadataPreservesExistingCompletedAndReadyStatuses() {
    Study completed = service.upsertStudyMetadata("CASE-COMPLETED", "completed", null);
    Study updatedCompleted =
        service.upsertStudyMetadata(
            "CASE-COMPLETED", new StudyMetadataDto("SPIDER-201", null, null, null, "alta"));
    Study ready = service.upsertStudyMetadata("CASE-READY", "ready", null);
    Study updatedReady =
        service.upsertStudyMetadata(
            "CASE-READY", new StudyMetadataDto("SPIDER-202", null, null, null, "media"));

    assertEquals(completed.id(), updatedCompleted.id());
    assertEquals("completed", updatedCompleted.status());
    assertEquals("high", updatedCompleted.reviewPriority());
    assertEquals(ready.id(), updatedReady.id());
    assertEquals("ready", updatedReady.status());
    assertEquals("medium", updatedReady.reviewPriority());
  }

  @Test
  void newStudyCanStartCreatedAndUnknownPriorityIsRejected() {
    Study created = service.upsertStudyMetadata("CASE-NEW-CREATED", "created", null);
    assertEquals("created", created.status());
    assertEquals("medium", created.reviewPriority());

    StudyMetadataException ex =
        assertThrows(
            StudyMetadataException.class,
            () ->
                service.upsertStudyMetadata(
                    "CASE-BAD-PRIORITY",
                    "created",
                    new StudyMetadataDto("SPIDER-303", null, null, null, "urgente")));
    assertEquals("INVALID_REVIEW_PRIORITY", ex.code());
  }

  @Test
  void nullMetadataPreservesExistingValuesAndRunPersistenceDoesNotResetStatus() {
    Study ready =
        service.upsertStudyMetadata(
            "CASE-RUN-READY",
            "ready",
            new StudyMetadataDto(
                "SPIDER-404", LocalDate.parse("2026-07-26"), "MRI", "Before run", "high"));

    Study preserved = service.upsertStudyMetadata("CASE-RUN-READY", null);
    assertEquals(ready.subjectRef(), preserved.subjectRef());
    assertEquals(ready.studyDate(), preserved.studyDate());
    assertEquals("ready", preserved.status());

    new MultiplanarRunPersistenceService(service)
        .persistSuccessfulRun(
            new MultiplanarRunRequestDto(
                "CASE-RUN-READY",
                "input-sag",
                null,
                null,
                null,
                "sagittal_spider",
                "axial_t2_alkafri",
                false,
                Map.of("inferenceMode", "real_baseline")),
            null,
            readyRunFixture());

    assertEquals("ready", repository.findStudyByCaseId("CASE-RUN-READY").orElseThrow().status());
  }

  private CanonicalMultiplanarRun readyRunFixture() {
    CanonicalPlaneRun sagittal =
        new CanonicalPlaneRun(
            "run-sag-ready",
            "sagittal",
            "completed",
            "real_baseline",
            false,
            null,
            Map.of("modelKey", "sagittal_spider", "artifactHash", "sha256:sag"),
            Map.of(),
            Map.of(),
            List.of(),
            List.of(Map.of("assetName", "overlay.png")),
            List.of(),
            List.of(),
            List.of(),
            Map.of());
    Map<String, CanonicalPlaneRun> planes = new LinkedHashMap<>();
    planes.put("sagittal", sagittal);
    return new CanonicalMultiplanarRun(
        "multiplanar_run_ready",
        "multiplanar-run-v1",
        "multi-ready",
        "trace-ready",
        "CASE-RUN-READY",
        "sagittal_only",
        "real_baseline",
        "real_baseline",
        List.of("sagittal"),
        List.of("sagittal"),
        false,
        null,
        Map.of(),
        planes,
        Map.of(),
        Map.of(),
        Map.of("status", "pending"),
        new CanonicalMultiplanarRun.Governance(true, true, true, false));
  }
}
