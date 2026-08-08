package ar.edu.uade.pfi.backend.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import ar.edu.uade.pfi.backend.domain.StudyRun;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

class DiscDegenerativeFindingsPersistenceServiceTest {
  private final ObjectMapper mapper = new ObjectMapper();

  @Test
  void persistsValidatedPredictionUnderDedicatedImmutableSnapshotKey() throws Exception {
    StudyRunService runs = org.mockito.Mockito.mock(StudyRunService.class);
    StudyRun run = run(Map.of("schemaVersion", "pfi.backend-run-snapshot.v2"));
    when(runs.findRunByMultiplanarRunId("multi-1")).thenReturn(Optional.of(run));
    when(runs.updateRunMetricsSnapshot(eq("multi-1"), org.mockito.ArgumentMatchers.anyMap()))
        .thenAnswer(invocation -> run(invocation.getArgument(1)));

    DiscDegenerativeFindingsPersistenceService service =
        new DiscDegenerativeFindingsPersistenceService(runs);
    Map<String, Object> updated = service.persistImmutable("multi-1", validResponse());

    assertEquals("pfi.backend-run-snapshot.v2", updated.get("schemaVersion"));
    assertEquals(true, updated.containsKey("discDegenerativeFindings"));
    assertEquals(true, updated.containsKey("discDegenerativeGovernance"));
    verify(runs).updateRunMetricsSnapshot(eq("multi-1"), org.mockito.ArgumentMatchers.anyMap());
  }

  @Test
  void identicalRetryIsIdempotent() throws Exception {
    StudyRunService runs = org.mockito.Mockito.mock(StudyRunService.class);
    Map<String, Object> response = validResponse();
    @SuppressWarnings("unchecked")
    Map<String, Object> disc = (Map<String, Object>) response.get("discDegenerativeFindings");
    StudyRun run = run(Map.of("discDegenerativeFindings", disc));
    when(runs.findRunByMultiplanarRunId("multi-1")).thenReturn(Optional.of(run));

    DiscDegenerativeFindingsPersistenceService service =
        new DiscDegenerativeFindingsPersistenceService(runs);
    service.persistImmutable("multi-1", response);

    verify(runs, never())
        .updateRunMetricsSnapshot(eq("multi-1"), org.mockito.ArgumentMatchers.anyMap());
  }

  @Test
  void rejectsDifferentSecondPrediction() throws Exception {
    StudyRunService runs = org.mockito.Mockito.mock(StudyRunService.class);
    Map<String, Object> first = validResponse();
    @SuppressWarnings("unchecked")
    Map<String, Object> disc = (Map<String, Object>) first.get("discDegenerativeFindings");
    StudyRun run = run(Map.of("discDegenerativeFindings", disc));
    when(runs.findRunByMultiplanarRunId("multi-1")).thenReturn(Optional.of(run));

    Map<String, Object> second = validResponse();
    @SuppressWarnings("unchecked")
    Map<String, Object> secondDisc = (Map<String, Object>) second.get("discDegenerativeFindings");
    @SuppressWarnings("unchecked")
    List<Map<String, Object>> findings = (List<Map<String, Object>>) secondDisc.get("findings");
    findings.get(0).put("findingId", "different-id");

    DiscDegenerativeFindingsPersistenceService service =
        new DiscDegenerativeFindingsPersistenceService(runs);
    ResponseStatusException error =
        assertThrows(
            ResponseStatusException.class, () -> service.persistImmutable("multi-1", second));
    assertEquals(409, error.getStatusCode().value());
  }

  @Test
  void rejectsUnexpectedCheckpointHashAsBadGateway() throws Exception {
    StudyRunService runs = org.mockito.Mockito.mock(StudyRunService.class);
    when(runs.findRunByMultiplanarRunId("multi-1")).thenReturn(Optional.of(run(Map.of())));
    Map<String, Object> response = validResponse();
    @SuppressWarnings("unchecked")
    Map<String, Object> disc = (Map<String, Object>) response.get("discDegenerativeFindings");
    @SuppressWarnings("unchecked")
    List<Map<String, Object>> findings = (List<Map<String, Object>>) disc.get("findings");
    @SuppressWarnings("unchecked")
    Map<String, Object> model = (Map<String, Object>) findings.get(0).get("model");
    model.put("modelSha256", "0".repeat(64));

    DiscDegenerativeFindingsPersistenceService service =
        new DiscDegenerativeFindingsPersistenceService(runs);
    ResponseStatusException error =
        assertThrows(
            ResponseStatusException.class, () -> service.persistImmutable("multi-1", response));
    assertEquals(502, error.getStatusCode().value());
  }

  private Map<String, Object> validResponse() throws Exception {
    String json =
        """
            {
              "discDegenerativeFindings": {
                "schemaVersion": "pfi.disc-degenerative-findings.v1",
                "findings": [{
                  "findingId": "finding-1",
                  "findingType": "disc_bulging",
                  "anatomy": {"level": "L4-L5", "side": null},
                  "classification": {
                    "kind": "binary",
                    "label": "present",
                    "probabilities": {"absent": 0.2, "present": 0.8}
                  },
                  "evidence": {
                    "deploymentStatus": "supported_internal",
                    "evaluationDataset": "SPIDER_internal_test",
                    "externalValidationAvailable": false
                  },
                  "evaluation": {"status": "evaluated"},
                  "sourceSeries": [{"role": "sagittal_t2", "available": true, "positions": [3,4,5]}],
                  "localization": {
                    "source": "segmentation_derived_disc_level",
                    "researchOnly": true,
                    "automaticAnatomicalLocalizationValidated": false
                  },
                  "model": {
                    "modelId": "spider_degenerative_multitask_sagittal_t1_t2_2p5d",
                    "modelSha256": "16eccff327e6794b127fe372ecd03ea619a0f69d939b84ae1aa2e904191c6293"
                  },
                  "review": {"required": true, "status": "pending"},
                  "notClinicalDiagnosis": true
                }]
              },
              "humanReviewRequired": true,
              "notClinicalDiagnosis": true,
              "autonomousDiagnosis": false
            }
            """;
    return mapper.readValue(json, new TypeReference<Map<String, Object>>() {});
  }

  private StudyRun run(Map<String, Object> snapshot) {
    Instant now = Instant.parse("2026-08-07T00:00:00Z");
    return new StudyRun(
        "run-db-1",
        "study-1",
        "multi-1",
        "trace-1",
        "real_baseline",
        "real_baseline",
        "sagittal_spider",
        "axial_t2_alkafri",
        "",
        "",
        "sag-run",
        "ax-run",
        Map.of(),
        snapshot,
        List.of(),
        "completed",
        "pending",
        "",
        null,
        "",
        now,
        now);
  }
}
