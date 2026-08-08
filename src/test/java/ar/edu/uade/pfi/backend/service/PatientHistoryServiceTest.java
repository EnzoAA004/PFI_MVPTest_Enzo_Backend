package ar.edu.uade.pfi.backend.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ar.edu.uade.pfi.backend.domain.MeasurementCorrection;
import ar.edu.uade.pfi.backend.domain.Study;
import ar.edu.uade.pfi.backend.repository.InMemoryStudyRepository;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class PatientHistoryServiceTest {
  @Test
  @SuppressWarnings("unchecked")
  void usesDomainStudiesRealMeasurementsCorrectionsAndOrderingOnly() {
    InMemoryStudyRepository repository = new InMemoryStudyRepository();
    StudyRunService studyRunService = new StudyRunService(repository);
    Study older =
        studyRunService.upsertStudyMetadata(
            "CASE-SPIDER-OLD",
            "created",
            new ar.edu.uade.pfi.backend.dto.StudyMetadataDto(
                "SPIDER-101", LocalDate.parse("2026-06-01"), "MRI", "Old study", "medium"));
    Study newer =
        studyRunService.upsertStudyMetadata(
            "CASE-SPIDER-NEW",
            "created",
            new ar.edu.uade.pfi.backend.dto.StudyMetadataDto(
                "SPIDER-101", LocalDate.parse("2026-07-26"), "MRI", "New study", "high"));
    Study other =
        studyRunService.upsertStudyMetadata(
            "CASE-OTHER",
            "created",
            new ar.edu.uade.pfi.backend.dto.StudyMetadataDto(
                "SPIDER-999", LocalDate.parse("2026-07-27"), "MRI", "Other study", "medium"));

    var newerRun =
        studyRunService.createRunWithId(
            UUID.randomUUID().toString(),
            newer,
            "multi-new",
            "trace-new",
            "real_baseline",
            "real_baseline",
            "sagittal_spider",
            "",
            "sha256:sag",
            "",
            "run-sag-new",
            "",
            Map.of(),
            Map.of(
                "planes",
                Map.of(
                    "sagittal",
                    Map.of(
                        "measurements",
                        List.of(
                            Map.of(
                                "id",
                                "disc-height-l45",
                                "labelKey",
                                "Disc Height",
                                "value",
                                8.2,
                                "unit",
                                "mm"))))),
            List.of(),
            "completed",
            "accepted",
            "dra-demo",
            Instant.parse("2026-07-26T12:00:00Z"),
            "Aceptado");
    studyRunService.createRunWithId(
        UUID.randomUUID().toString(),
        older,
        "multi-old",
        "trace-old",
        "real_baseline",
        "real_baseline",
        "sagittal_spider",
        "",
        "sha256:sag",
        "",
        "run-sag-old",
        "",
        Map.of(),
        Map.of(),
        List.of(),
        "completed",
        "pending",
        "",
        null,
        "");
    studyRunService.createRunWithId(
        UUID.randomUUID().toString(),
        other,
        "multi-other",
        "trace-other",
        "real_baseline",
        "real_baseline",
        "sagittal_spider",
        "",
        "sha256:sag",
        "",
        "run-sag-other",
        "",
        Map.of(),
        Map.of(),
        List.of(),
        "completed",
        "pending",
        "",
        null,
        "");
    repository.saveReview(
        "multi-new",
        "edited",
        "dra-demo",
        Instant.parse("2026-07-26T13:00:00Z"),
        "Correccion registrada",
        List.of(
            new MeasurementCorrection(
                UUID.randomUUID().toString(),
                newerRun.id(),
                "disc-height-l45",
                "Disc Height",
                Map.of("value", 8.2, "unit", "mm"),
                Map.of("value", 8.5, "unit", "mm"),
                "Ajuste profesional",
                Instant.parse("2026-07-26T13:00:00Z"))));

    Map<String, Object> history = new PatientHistoryService(repository).history("SPIDER-101");
    List<Map<String, Object>> studies = (List<Map<String, Object>>) history.get("studies");

    assertEquals("postgres-domain", history.get("source"));
    assertEquals(2, studies.size());
    assertEquals("CASE-SPIDER-NEW", studies.get(0).get("caseId"));
    assertEquals("CASE-SPIDER-OLD", studies.get(1).get("caseId"));
    assertFalse(studies.stream().anyMatch(item -> "CASE-OTHER".equals(item.get("caseId"))));

    Map<String, List<Map<String, Object>>> measurements =
        (Map<String, List<Map<String, Object>>>) studies.get(0).get("measurementsByPlane");
    assertEquals(8.2, measurements.get("sagittal").get(0).get("aiValue"));
    assertEquals(1, ((List<?>) studies.get(0).get("corrections")).size());
    String serialized = history.toString();
    assertFalse(serialized.contains("lordosisAngle"));
    assertFalse(serialized.contains("canalDiameter"));
    assertTrue(((Map<String, Object>) history.get("summary")).containsKey("withStudyDate"));
  }

  @Test
  void missingSubjectRefReturnsEmptyDomainResponse() {
    Map<String, Object> history =
        new PatientHistoryService(new InMemoryStudyRepository()).history("SPIDER-404");
    assertEquals("ok", history.get("status"));
    assertEquals("postgres-domain", history.get("source"));
    assertEquals(List.of(), history.get("studies"));
    assertEquals(0, ((Map<?, ?>) history.get("summary")).get("totalStudies"));
  }
}
