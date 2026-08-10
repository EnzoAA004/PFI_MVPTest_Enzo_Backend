package ar.edu.uade.pfi.backend.service;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

import ar.edu.uade.pfi.backend.client.exception.AiMultiplanarContractViolationException;
import ar.edu.uade.pfi.backend.domain.CanonicalMultiplanarRun;
import ar.edu.uade.pfi.backend.domain.CanonicalPlaneRun;
import ar.edu.uade.pfi.backend.dto.MultiplanarRunRequestDto;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class MultiplanarV2RealBaselineValidatorTest {
  private final MultiplanarV2RealBaselineValidator validator =
      new MultiplanarV2RealBaselineValidator();

  @Test
  void validSagittalOnlyResponsePasses() {
    assertDoesNotThrow(
        () -> validator.validate(sagittalOnlyRequest(), root(new PlaneFixture()).build()));
  }

  @Test
  void schemaVersionIncorrectFails() {
    RootFixture root = root(new PlaneFixture());
    root.schemaVersion = "multiplanar-run-v1";
    assertFails(root);
  }

  @Test
  void statusNotCompletedFails() {
    RootFixture root = root(new PlaneFixture());
    root.status = "processing";
    assertFails(root);
  }

  @Test
  void syntheticTrueFailsInStrictReal() {
    RootFixture root = root(new PlaneFixture());
    root.synthetic = true;
    assertFails(root);
  }

  @Test
  void fallbackReasonPresentFails() {
    RootFixture root = root(new PlaneFixture());
    root.fallbackReason = "model_unavailable";
    assertFails(root);
  }

  @Test
  void sagittalArtifactHashIncorrectFails() {
    PlaneFixture plane = new PlaneFixture();
    plane.model.put("artifactHash", "not-the-expected-hash");
    assertFails(root(plane));
  }

  @Test
  void planeRunIdEqualToMultiplanarRunIdFails() {
    RootFixture root = root(new PlaneFixture());
    root.plane.planeRunId = root.multiplanarRunId;
    assertFails(root);
  }

  @Test
  void axialPresentWithoutBeingRequestedFails() {
    RootFixture root = root(new PlaneFixture());
    root.axial = new PlaneFixture();
    root.axial.plane = "axial";
    assertFails(root);
  }

  @Test
  void axialAbsentWhenRequestedFails() {
    RootFixture root = root(new PlaneFixture());
    root.requestedPlanes = List.of("sagittal", "axial");
    root.completedPlanes = List.of("sagittal", "axial");
    root.workspaceMode = "dual_plane_with_3d_context";
    // axial intentionally left null even though it was "requested"
    assertThrows(
        AiMultiplanarContractViolationException.class,
        () -> validator.validate(dualRequest(), root.build()));
  }

  @Test
  void selectedSliceIndexOutOfRangeFails() {
    PlaneFixture plane = new PlaneFixture();
    plane.input.put("selectedSliceIndex", 99);
    assertFails(root(plane));
  }

  @Test
  void coordinateSpaceZeroByZeroFails() {
    PlaneFixture plane = new PlaneFixture();
    plane.coordinateSpace.put("width", 0);
    plane.coordinateSpace.put("height", 0);
    assertFails(root(plane));
  }

  @Test
  void assetWithPathTraversalFails() {
    PlaneFixture plane = new PlaneFixture();
    plane.assets.add(
        mapOf(
            "assetName",
            "extra.png",
            "generated",
            true,
            "relativePath",
            "/assets/../../etc/passwd"));
    assertFails(root(plane));
  }

  @Test
  void assetWithExternalUrlFails() {
    PlaneFixture plane = new PlaneFixture();
    plane.assets.add(
        mapOf(
            "assetName",
            "extra.png",
            "generated",
            true,
            "relativePath",
            "https://attacker.example/x.png"));
    assertFails(root(plane));
  }

  @Test
  void measurementWithoutIdFails() {
    PlaneFixture plane = new PlaneFixture();
    plane.measurements.set(0, mapOf("labelKey", "no_id_measurement"));
    assertFails(root(plane));
  }

  @Test
  void incorrectGovernanceFails() {
    RootFixture root = root(new PlaneFixture());
    root.governance = new CanonicalMultiplanarRun.Governance(false, true, true, false);
    assertFails(root);

    RootFixture diagnosisGenerated = root(new PlaneFixture());
    diagnosisGenerated.governance = new CanonicalMultiplanarRun.Governance(true, true, true, true);
    assertFails(diagnosisGenerated);
  }

  @Test
  void frozenSagittalSpiderArtifactRequiresExactlyThreeThreeNineCounts() {
    PlaneFixture plane = new PlaneFixture();
    plane.masks.add(mapOf("id", "extra-mask", "classKey", "extra"));
    assertFails(root(plane));
  }

  @Test
  void frozenArtifactCountIsNotEnforcedForADifferentModelVersion() {
    PlaneFixture plane = new PlaneFixture();
    plane.model.put("version", "sagittal-spider-experimental-v2");
    plane.masks.add(mapOf("id", "extra-mask", "classKey", "extra"));
    // A different release is not held to the frozen 3/3/9 count, but it still must
    // match the pinned modelVersion/hash constants below — so mismatch is expected
    // to fail for a DIFFERENT reason (model.version), not for the mask count.
    AiMultiplanarContractViolationException ex =
        assertThrows(
            AiMultiplanarContractViolationException.class,
            () -> validator.validate(sagittalOnlyRequest(), root(plane).build()));
    assertDoesNotThrow(
        () -> {
          if (ex.getMessage().contains("exactamente 3 masks")) {
            throw new AssertionError(
                "frozen-count check must not apply to a different model version");
          }
        });
  }

  private void assertFails(RootFixture root) {
    assertThrows(
        AiMultiplanarContractViolationException.class,
        () -> validator.validate(sagittalOnlyRequest(), root.build()));
  }

  private RootFixture root(PlaneFixture sagittal) {
    RootFixture root = new RootFixture();
    root.plane = sagittal;
    return root;
  }

  private MultiplanarRunRequestDto sagittalOnlyRequest() {
    return new MultiplanarRunRequestDto(
        "CASE-001",
        "inp_sagittal_001",
        null,
        null,
        null,
        "sagittal_spider",
        "axial_t2_alkafri",
        false,
        Map.of("inferenceMode", "real_baseline"));
  }

  private MultiplanarRunRequestDto dualRequest() {
    return new MultiplanarRunRequestDto(
        "CASE-001",
        "inp_sagittal_001",
        "inp_axial_001",
        null,
        null,
        "sagittal_spider",
        "axial_t2_alkafri",
        false,
        Map.of("inferenceMode", "real_baseline"));
  }

  private Map<String, Object> mapOf(Object... kv) {
    Map<String, Object> map = new LinkedHashMap<>();
    for (int i = 0; i < kv.length; i += 2) {
      map.put(String.valueOf(kv[i]), kv[i + 1]);
    }
    return map;
  }

  private List<Map<String, Object>> assetsFor(String planeRunId) {
    List<Map<String, Object>> assets = new ArrayList<>();
    for (String name : List.of("input.png", "overlay.png", "mask.npy", "confidence.npy")) {
      assets.add(
          mapOf(
              "assetName",
              name,
              "generated",
              true,
              "relativePath",
              "/assets/" + planeRunId + "/sagittal/" + name));
    }
    return assets;
  }

  private List<Map<String, Object>> countedList(int count, String idPrefix, String secondKey) {
    List<Map<String, Object>> list = new ArrayList<>();
    for (int i = 0; i < count; i++) {
      list.add(mapOf("id", idPrefix + i, secondKey, idPrefix + i + "_key"));
    }
    return list;
  }

  /** Mutable test-only mirror of CanonicalPlaneRun's constructor args. */
  private final class PlaneFixture {
    String planeRunId = "run-sag-001";
    String plane = "sagittal";
    String status = "ready";
    String effectiveInferenceMode = "real_baseline";
    boolean synthetic = false;
    String fallbackReason = null;
    Map<String, Object> model =
        new LinkedHashMap<>(
            Map.of(
                "key",
                "sagittal_spider",
                "version",
                "sagittal-spider-final-v1",
                "artifactHash",
                MultiplanarV2RealBaselineValidator.SAGITTAL_ARTIFACT_HASH,
                "baselineReady",
                true,
                "availableForRealInference",
                true,
                "manifestValid",
                true));
    Map<String, Object> input =
        new LinkedHashMap<>(
            Map.of(
                "inputId",
                "inp_sagittal_001",
                "nativeShape",
                List.of(352, 384, 17),
                "canonicalShape",
                List.of(352, 384, 17),
                "selectedSliceIndex",
                8));
    Map<String, Object> coordinateSpace = new LinkedHashMap<>(Map.of("width", 256, "height", 256));
    List<Map<String, Object>> series = new ArrayList<>(List.of(mapOf("seriesId", "s1")));
    List<Map<String, Object>> assets = assetsFor(planeRunId);
    List<Map<String, Object>> masks = countedList(3, "mask", "classKey");
    List<Map<String, Object>> landmarks = countedList(3, "landmark", "labelKey");
    List<Map<String, Object>> measurements = countedList(9, "measurement", "labelKey");
    Map<String, Object> quality =
        new LinkedHashMap<>(Map.of("maskCount", 3, "landmarkCount", 3, "measurementCount", 9));

    CanonicalPlaneRun build() {
      return new CanonicalPlaneRun(
          planeRunId,
          plane,
          status,
          effectiveInferenceMode,
          synthetic,
          fallbackReason,
          model,
          input,
          coordinateSpace,
          series,
          assets,
          masks,
          landmarks,
          measurements,
          quality);
    }
  }

  /** Mutable test-only mirror of CanonicalMultiplanarRun's constructor args. */
  private final class RootFixture {
    String status = "completed";
    String schemaVersion = "pfi.multiplanar-run.v2";
    String multiplanarRunId = "multi-v2-001";
    String traceId = "trace-v2-001";
    String caseId = "CASE-001";
    String workspaceMode = "sagittal_only";
    String requestedInferenceMode = "real_baseline";
    String effectiveInferenceMode = "real_baseline";
    List<String> requestedPlanes = List.of("sagittal");
    List<String> completedPlanes = List.of("sagittal");
    boolean synthetic = false;
    String fallbackReason = null;
    PlaneFixture plane;
    PlaneFixture axial;
    CanonicalMultiplanarRun.Governance governance =
        new CanonicalMultiplanarRun.Governance(true, true, true, false);

    CanonicalMultiplanarRun build() {
      Map<String, CanonicalPlaneRun> planes = new LinkedHashMap<>();
      if (plane != null) planes.put("sagittal", plane.build());
      if (axial != null) planes.put("axial", axial.build());
      return new CanonicalMultiplanarRun(
          status,
          schemaVersion,
          multiplanarRunId,
          traceId,
          caseId,
          workspaceMode,
          requestedInferenceMode,
          effectiveInferenceMode,
          requestedPlanes,
          completedPlanes,
          synthetic,
          fallbackReason,
          Map.of(),
          planes,
          Map.of(),
          Map.of(),
          Map.of("required", true, "status", "pending"),
          governance);
    }
  }
}
