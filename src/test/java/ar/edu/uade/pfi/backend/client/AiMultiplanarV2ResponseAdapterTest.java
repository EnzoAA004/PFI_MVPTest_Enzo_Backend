package ar.edu.uade.pfi.backend.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ar.edu.uade.pfi.backend.domain.CanonicalMultiplanarRun;
import ar.edu.uade.pfi.backend.domain.CanonicalPlaneRun;
import ar.edu.uade.pfi.backend.dto.AiMultiplanarV2ResponseDto;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.InputStream;
import org.junit.jupiter.api.Test;

class AiMultiplanarV2ResponseAdapterTest {
  private final ObjectMapper objectMapper = new ObjectMapper();
  private final AiMultiplanarV2ResponseAdapter adapter =
      new AiMultiplanarV2ResponseAdapter(objectMapper);

  @Test
  void adaptsSagittalOnlyRealBaselineFixtureToCanonicalModel() throws Exception {
    AiMultiplanarV2ResponseDto response;
    try (InputStream json =
        getClass().getResourceAsStream("/contracts/ai-module-multiplanar-v2-real-baseline.json")) {
      response = objectMapper.readValue(json, AiMultiplanarV2ResponseDto.class);
    }

    CanonicalMultiplanarRun canonical = adapter.toCanonical(response);

    assertEquals("completed", canonical.status());
    assertEquals("pfi.multiplanar-run.v2", canonical.schemaVersion());
    assertEquals("multi-32d66dabd290b661c709", canonical.multiplanarRunId());
    assertEquals("P9A-SPIDER-101-T2", canonical.caseId());
    assertEquals("sagittal_only", canonical.workspaceMode());
    assertFalse(canonical.synthetic());
    assertNull(canonical.fallbackReason());
    assertEquals(java.util.List.of("sagittal"), canonical.requestedPlanes());
    assertEquals(java.util.List.of("sagittal"), canonical.completedPlanes());
    assertNull(canonical.axial());

    assertTrue(canonical.governance().humanReviewRequired());
    assertTrue(canonical.governance().notClinicalDiagnosis());
    assertFalse(canonical.governance().diagnosisGenerated());

    CanonicalPlaneRun sagittal = canonical.sagittal();
    assertEquals("bec20aa91f96c9cd", sagittal.planeRunId());
    assertEquals("sagittal", sagittal.plane());
    assertEquals("real_baseline", sagittal.effectiveInferenceMode());
    assertEquals("sagittal_spider", sagittal.model().get("key"));
    assertEquals("sagittal-spider-final-v1", sagittal.model().get("version"));
    assertEquals(
        "cf11dcc0ad77a7c787e64a796a2fd7398ef906add461cef4b3d61f1a5238e944",
        sagittal.model().get("artifactHash"));
    assertEquals(3, sagittal.masks().size());
    assertEquals(3, sagittal.landmarks().size());
    assertEquals(9, sagittal.measurements().size());
    assertEquals(4, sagittal.assets().size());
    assertTrue(
        sagittal.assets().stream().anyMatch(asset -> "mask.npy".equals(asset.get("assetName"))));
    assertEquals(java.util.List.of(352, 384, 17), sagittal.input().get("nativeShape"));
  }

  @Test
  void returnsNullForNullResponse() {
    assertEquals(null, adapter.toCanonical(null));
  }
}
