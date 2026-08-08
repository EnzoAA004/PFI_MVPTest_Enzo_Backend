package ar.edu.uade.pfi.backend.controller;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import ar.edu.uade.pfi.backend.client.AiProductCheckpointClient;
import ar.edu.uade.pfi.backend.dto.DiscDegenerativeProductRequestDto;
import ar.edu.uade.pfi.backend.dto.DiscSegmentationSourceDto;
import ar.edu.uade.pfi.backend.service.DiscDegenerativeFindingsPersistenceService;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * P10.9 hardening: {@code classification.probabilities} is needed internally (upstream contract
 * validation, traceability, persistence, audit) but must never reach the public POST response.
 * {@code DiscDegenerativeFindingsPersistenceService} is mocked here, not exercised, so this test
 * only proves what the controller returns to a caller -- the persistence contract itself is covered
 * by DiscDegenerativeFindingsPersistenceServiceTest.
 */
class AiProductCheckpointControllerTest {
  private final ObjectMapper mapper = new ObjectMapper();

  @Test
  void publicPredictDiscDegenerativeResponseNeverContainsProbabilitiesButKeepsLabelAndGovernance()
      throws Exception {
    AiProductCheckpointClient client = mock(AiProductCheckpointClient.class);
    DiscDegenerativeFindingsPersistenceService persistence =
        mock(DiscDegenerativeFindingsPersistenceService.class);
    Map<String, Object> upstream = upstreamPredictionWithProbabilities();
    when(client.predictDiscDegenerativeFromSegmentation(any())).thenReturn(upstream);

    AiProductCheckpointController controller =
        new AiProductCheckpointController(client, persistence);
    DiscDegenerativeProductRequestDto request =
        new DiscDegenerativeProductRequestDto(
            "multi-1",
            "CASE-1",
            List.of(new DiscSegmentationSourceDto("sagittal_t2", "input-1", "seg-run-1")));

    Map<String, Object> publicResponse = controller.predictDiscDegenerative(request);

    // Internal contract: persistence still receives the FULL upstream prediction,
    // probabilities included -- persistImmutable's own validator requires them.
    @SuppressWarnings("unchecked")
    ArgumentCaptor<Map<String, Object>> persistedCaptor = ArgumentCaptor.forClass(Map.class);
    verify(persistence).persistImmutable(eq("multi-1"), persistedCaptor.capture());
    assertTrue(
        hasProbabilities(persistedCaptor.getValue()),
        "persistence must still receive probabilities");

    // Public contract: the caller-facing response never carries probabilities.
    assertFalse(
        hasProbabilities(publicResponse), "public POST response must not contain probabilities");

    @SuppressWarnings("unchecked")
    Map<String, Object> disc = (Map<String, Object>) publicResponse.get("discDegenerativeFindings");
    @SuppressWarnings("unchecked")
    Map<String, Object> finding = ((List<Map<String, Object>>) disc.get("findings")).get(0);
    @SuppressWarnings("unchecked")
    Map<String, Object> classification = (Map<String, Object>) finding.get("classification");
    assertEquals("present", classification.get("label"));

    assertEquals(true, publicResponse.get("humanReviewRequired"));
    assertEquals(true, publicResponse.get("notClinicalDiagnosis"));
    assertEquals(false, publicResponse.get("autonomousDiagnosis"));
    @SuppressWarnings("unchecked")
    Map<String, Object> persistenceStatus = (Map<String, Object>) publicResponse.get("persistence");
    assertEquals("persisted_immutable", persistenceStatus.get("status"));
  }

  @SuppressWarnings("unchecked")
  private boolean hasProbabilities(Map<String, Object> response) {
    Object disc = response.get("discDegenerativeFindings");
    if (!(disc instanceof Map<?, ?> discMap)) return false;
    Object findingsRaw = ((Map<String, Object>) discMap).get("findings");
    if (!(findingsRaw instanceof List<?> findings)) return false;
    for (Object item : findings) {
      Map<String, Object> finding = (Map<String, Object>) item;
      Object classification = finding.get("classification");
      if (classification instanceof Map<?, ?> map && map.containsKey("probabilities")) return true;
    }
    return false;
  }

  private Map<String, Object> upstreamPredictionWithProbabilities() throws Exception {
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
                  "evidence": {"deploymentStatus": "supported_internal"},
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
}
