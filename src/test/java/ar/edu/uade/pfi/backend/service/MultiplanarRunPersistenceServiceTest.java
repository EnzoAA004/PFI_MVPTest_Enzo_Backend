package ar.edu.uade.pfi.backend.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import ar.edu.uade.pfi.backend.client.AiServiceOperations;
import ar.edu.uade.pfi.backend.controller.AiMultiplanarController;
import ar.edu.uade.pfi.backend.domain.CanonicalMultiplanarRun;
import ar.edu.uade.pfi.backend.domain.CanonicalPlaneRun;
import ar.edu.uade.pfi.backend.domain.Study;
import ar.edu.uade.pfi.backend.domain.StudyRun;
import ar.edu.uade.pfi.backend.dto.MultiplanarRunRequestDto;
import ar.edu.uade.pfi.backend.repository.PostgresStudyRepository;
import ar.edu.uade.pfi.backend.web.error.ApiExceptionHandler;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers(disabledWithoutDocker = true)
class MultiplanarRunPersistenceServiceTest {
  @Container
  static final PostgreSQLContainer<?> postgres =
      new PostgreSQLContainer<>("postgres:16-alpine")
          .withDatabaseName("pfi_be006")
          .withUsername("pfi")
          .withPassword("pfi");

  @Test
  void persistsMultiplanarRunResponseAndRecoversItByRunIdAndTraceId() {
    PostgresStudyRepository repository =
        new PostgresStudyRepository(
            new ObjectMapper(),
            postgres.getJdbcUrl()
                + "&user="
                + postgres.getUsername()
                + "&password="
                + postgres.getPassword(),
            true);
    StudyRunService studyRunService = new StudyRunService(repository);
    MultiplanarRunPersistenceService persistence =
        new MultiplanarRunPersistenceService(studyRunService);

    MultiplanarRunRequestDto request =
        new MultiplanarRunRequestDto(
            "CASE-BE-006",
            "input-sag-be006",
            "input-ax-be006",
            null,
            null,
            "sagittal_spider",
            "axial_t2_alkafri",
            false,
            Map.of("inferenceMode", "real_baseline", "allowContractFallback", false));
    CanonicalMultiplanarRun response = response();

    persistence.persistSuccessfulRun(request, response);

    Study study = studyRunService.findStudyByCaseId("CASE-BE-006").orElseThrow();
    StudyRun byRunId = studyRunService.findRunByMultiplanarRunId("multi-be006").orElseThrow();
    StudyRun byTraceId = studyRunService.findRunByTraceId("trace-be006").orElseThrow();

    assertEquals(study.id(), byRunId.studyId());
    assertEquals(byRunId.id(), byTraceId.id());
    assertEquals("trace-be006", byRunId.traceId());
    assertEquals("real_baseline", byRunId.requestedInferenceMode());
    assertEquals("real_baseline", byRunId.effectiveInferenceMode());
    assertEquals("sagittal_spider", byRunId.sagittalModelKey());
    assertEquals("axial_t2_alkafri", byRunId.axialModelKey());
    assertEquals("sha256:sag-be006", byRunId.sagittalArtifactHash());
    assertEquals("sha256:ax-be006", byRunId.axialArtifactHash());
    assertEquals("run-sag-be006", byRunId.sagittalRunId());
    assertEquals("run-ax-be006", byRunId.axialRunId());
    assertEquals("pending", byRunId.reviewStatus());
    assertFalse(byRunId.metricsSnapshot().isEmpty());
    assertEquals(2, studyRunService.findInputs(study).size());
    assertTrue(
        byRunId.artifacts().stream()
            .anyMatch(
                artifact ->
                    artifact.runId().equals("run-sag-be006")
                        && artifact.assetName().equals("overlay.png")));
    assertTrue(
        byRunId.artifacts().stream()
            .anyMatch(
                artifact ->
                    artifact.runId().equals("run-ax-be006")
                        && artifact.assetName().equals("mask-preview.png")));
    assertTrue(
        byRunId.artifacts().stream()
            .anyMatch(
                artifact ->
                    artifact.runId().equals("multi-be006")
                        && artifact.plane().equals("workspace")
                        && artifact.assetName().equals("lumbar-3d-mesh.json")
                        && artifact.contentType().equals("application/json")));
    assertTrue(
        byRunId.artifacts().stream()
            .noneMatch(
                artifact ->
                    artifact.artifactRef().contains("/")
                        || artifact.artifactRef().contains("\\")
                        || artifact.artifactRef().contains("..")));

    Map<String, Object> snapshot = byRunId.metricsSnapshot();
    Map<String, Object> threeD = (Map<String, Object>) snapshot.get("threeD");
    Map<String, Object> reconstruction = (Map<String, Object>) threeD.get("reconstruction");
    assertEquals(true, threeD.get("enabled"));
    assertEquals("experimental_geometric_proxy", reconstruction.get("kind"));
    assertEquals("dual_plane_bbox_proxy", reconstruction.get("method"));
    assertEquals(false, reconstruction.get("anatomicalReconstruction"));
    assertEquals(false, reconstruction.get("volumetricReconstruction"));
    assertEquals("local_proxy_space", reconstruction.get("coordinateSystem"));
    assertEquals("config", reconstruction.get("mappingSource"));
    assertEquals(false, reconstruction.get("mappingValidated"));

    Map<String, Object> planesSnapshot = (Map<String, Object>) snapshot.get("planes");
    Map<String, Object> sagittalSnapshot = (Map<String, Object>) planesSnapshot.get("sagittal");
    Map<String, Object> axialSnapshot = (Map<String, Object>) planesSnapshot.get("axial");
    Map<String, Object> sagittalModel = (Map<String, Object>) sagittalSnapshot.get("model");
    Map<String, Object> axialModel = (Map<String, Object>) axialSnapshot.get("model");
    assertEquals(true, sagittalModel.get("baselineReady"));
    assertEquals("real_baseline_ready", sagittalModel.get("readiness"));
    assertEquals(false, axialModel.get("baselineReady"));
    assertEquals(true, axialModel.get("availableForRealInference"));
    assertEquals("real_candidate_ready", axialModel.get("readiness"));
    assertEquals("axial_candidate_runtime_ready", axialModel.get("runtimeQualification"));
    assertEquals(false, axialModel.get("qualityGatePassed"));
  }

  @Test
  @SuppressWarnings("unchecked")
  void persistsSagittalOnlyRunWithoutAxialInputRunOrAssets() {
    PostgresStudyRepository repository =
        new PostgresStudyRepository(
            new ObjectMapper(),
            postgres.getJdbcUrl()
                + "&user="
                + postgres.getUsername()
                + "&password="
                + postgres.getPassword(),
            true);
    StudyRunService studyRunService = new StudyRunService(repository);
    MultiplanarRunPersistenceService persistence =
        new MultiplanarRunPersistenceService(studyRunService);

    MultiplanarRunRequestDto request =
        new MultiplanarRunRequestDto(
            "CASE-BE-SAG-ONLY",
            "input-sag-only",
            null,
            null,
            null,
            "sagittal_spider",
            "axial_t2_alkafri",
            false,
            Map.of(
                "inferenceMode", "real_baseline",
                "requestedInferenceMode", "real_baseline",
                "allowContractFallback", false,
                "axialMode", "optional_not_provided"));
    CanonicalMultiplanarRun response = sagittalOnlyResponse();

    persistence.persistSuccessfulRun(request, response);

    Study study = studyRunService.findStudyByCaseId("CASE-BE-SAG-ONLY").orElseThrow();
    StudyRun byRunId = studyRunService.findRunByMultiplanarRunId("multi-sag-only").orElseThrow();
    StudyRun byTraceId = studyRunService.findRunByTraceId("trace-sag-only").orElseThrow();

    assertEquals(study.id(), byRunId.studyId());
    assertEquals(byRunId.id(), byTraceId.id());
    assertEquals("trace-sag-only", byRunId.traceId());
    assertEquals("real_baseline", byRunId.requestedInferenceMode());
    assertEquals("mixed", byRunId.effectiveInferenceMode());
    assertEquals("sagittal_spider", byRunId.sagittalModelKey());
    assertEquals("axial_t2_alkafri", byRunId.axialModelKey());
    assertEquals(
        MultiplanarRealBaselineContractValidator.SAGITTAL_ARTIFACT_HASH,
        byRunId.sagittalArtifactHash());
    assertEquals("", byRunId.axialArtifactHash());
    assertEquals("run-sag-only", byRunId.sagittalRunId());
    assertEquals("", byRunId.axialRunId());
    assertEquals(1, studyRunService.findInputs(study).size());
    assertTrue(
        byRunId.artifacts().stream().allMatch(artifact -> artifact.plane().equals("sagittal")));
    assertTrue(
        byRunId.artifacts().stream()
            .anyMatch(artifact -> artifact.assetName().equals("overlay.png")));

    Map<String, Object> snapshot = byRunId.metricsSnapshot();
    assertEquals("pfi.backend-run-snapshot.v2", snapshot.get("schemaVersion"));
    assertEquals("multiplanar-run-v1", snapshot.get("sourceSchemaVersion"));
    Map<String, Object> planesSnapshot = (Map<String, Object>) snapshot.get("planes");
    Map<String, Object> sagittalSnapshot = (Map<String, Object>) planesSnapshot.get("sagittal");
    Map<String, Object> inputSnapshot = (Map<String, Object>) sagittalSnapshot.get("input");
    Map<String, Object> modelSnapshot = (Map<String, Object>) sagittalSnapshot.get("model");
    assertEquals("input-sag-only", inputSnapshot.get("inputId"));
    assertEquals("sagittal-spider-final-v1", modelSnapshot.get("modelVersion"));
    assertEquals(
        MultiplanarRealBaselineContractValidator.SAGITTAL_ARTIFACT_HASH,
        modelSnapshot.get("artifactHash"));
    assertEquals(8, ((Number) inputSnapshot.get("selectedSliceIndex")).intValue());
    assertEquals(List.of(352, 384, 17), inputSnapshot.get("nativeShape"));
    Map<String, Object> governanceSnapshot = (Map<String, Object>) snapshot.get("governance");
    assertEquals(true, governanceSnapshot.get("humanReviewRequired"));
    assertEquals(true, governanceSnapshot.get("notClinicalDiagnosis"));

    List<Map<String, Object>> measurements =
        (List<Map<String, Object>>) sagittalSnapshot.get("measurements");
    assertEquals(1, measurements.size());
    Map<String, Object> measurement = measurements.get(0);
    assertEquals("canalAreaMm2", measurement.get("id"));
    assertEquals(82.4, measurement.get("aiValue"));
    assertEquals(82.4, measurement.get("value"));
    assertEquals("AI", measurement.get("source"));
    assertEquals("sagittal", measurement.get("plane"));
    assertNull(measurement.get("level"));
  }

  @Test
  void syntheticRunIsNeverPersistedWithTheSameStatusAsARealCompletedRun() {
    PostgresStudyRepository repository =
        new PostgresStudyRepository(
            new ObjectMapper(),
            postgres.getJdbcUrl()
                + "&user="
                + postgres.getUsername()
                + "&password="
                + postgres.getPassword(),
            true);
    StudyRunService studyRunService = new StudyRunService(repository);
    MultiplanarRunPersistenceService persistence =
        new MultiplanarRunPersistenceService(studyRunService);

    MultiplanarRunRequestDto request =
        new MultiplanarRunRequestDto(
            "CASE-BE-SYNTHETIC",
            "input-sag-synthetic",
            null,
            null,
            null,
            "sagittal_spider",
            "axial_t2_alkafri",
            true,
            Map.of("inferenceMode", "demo"));
    CanonicalPlaneRun sagittal =
        new CanonicalPlaneRun(
            "run-sag-synthetic",
            "sagittal",
            "ready",
            "demo",
            true,
            "model_unavailable",
            Map.of("modelKey", "sagittal_spider"),
            Map.of("inputId", "input-sag-synthetic"),
            Map.of(),
            List.of(),
            List.of(),
            List.of(),
            List.of(),
            List.of(),
            Map.of());
    Map<String, CanonicalPlaneRun> planes = new LinkedHashMap<>();
    planes.put("sagittal", sagittal);
    CanonicalMultiplanarRun response =
        new CanonicalMultiplanarRun(
            "completed",
            "pfi.multiplanar-run.v2",
            "multi-synthetic",
            "trace-synthetic",
            "CASE-BE-SYNTHETIC",
            "sagittal_only",
            "demo",
            "demo",
            List.of("sagittal"),
            List.of("sagittal"),
            true,
            "model_unavailable",
            Map.of(),
            planes,
            Map.of(),
            Map.of(),
            Map.of("status", "pending"),
            new CanonicalMultiplanarRun.Governance(true, true, true, false));

    persistence.persistSuccessfulRun(request, response);

    StudyRun persisted = studyRunService.findRunByMultiplanarRunId("multi-synthetic").orElseThrow();
    assertEquals("completed_synthetic", persisted.status());
    assertFalse("completed".equals(persisted.status()));
  }

  @Test
  void twoConsecutiveMultiplanarPostRequestsRemainIdempotent() throws Exception {
    PostgresStudyRepository repository =
        new PostgresStudyRepository(
            new ObjectMapper(),
            postgres.getJdbcUrl()
                + "&user="
                + postgres.getUsername()
                + "&password="
                + postgres.getPassword(),
            true);
    StudyRunService studyRunService = new StudyRunService(repository);
    MultiplanarRunPersistenceService persistence =
        new MultiplanarRunPersistenceService(studyRunService);
    AiServiceOperations ai = org.mockito.Mockito.mock(AiServiceOperations.class);
    when(ai.runMultiplanar(any())).thenReturn(sagittalOnlyResponse(), sagittalOnlyResponse());
    MockMvc mockMvc =
        MockMvcBuilders.standaloneSetup(new AiMultiplanarController(ai, persistence, null))
            .setControllerAdvice(new ApiExceptionHandler())
            .build();

    String body =
        """
            {
              "caseId": "CASE-BE-SAG-ONLY",
              "sagittalInputId": "input-sag-only",
              "allowContractFallback": false,
              "metadata": {
                "inferenceMode": "real_baseline",
                "axialMode": "optional_not_provided"
              }
            }
            """;

    mockMvc
        .perform(
            post("/api/ai/multiplanar/run").contentType(MediaType.APPLICATION_JSON).content(body))
        .andExpect(status().isOk());
    mockMvc
        .perform(
            post("/api/ai/multiplanar/run").contentType(MediaType.APPLICATION_JSON).content(body))
        .andExpect(status().isOk());

    StudyRun recovered = studyRunService.findRunByMultiplanarRunId("multi-sag-only").orElseThrow();
    assertEquals("trace-sag-only", recovered.traceId());
    assertEquals("run-sag-only", recovered.sagittalRunId());
    assertTrue(
        recovered.artifacts().stream()
            .allMatch(artifact -> artifact.studyRunId().equals(recovered.id())));
  }

  /**
   * P9-B.2.1 atomicity gap: the metricsSnapshot (threeD.enabled=true, computed from the AI Module's
   * own response) is persisted before the workspace mesh is actually downloaded/validated. If that
   * download later fails or the mesh content is rejected, the backend must never keep publishing
   * threeD.enabled=true with a URL that has no stored content behind it.
   */
  @Test
  @SuppressWarnings("unchecked")
  void downgradesThreeDToBlockedWhenWorkspaceMeshFailsValidationAfterPersistence()
      throws Exception {
    ar.edu.uade.pfi.backend.repository.InMemoryStudyRepository repository =
        new ar.edu.uade.pfi.backend.repository.InMemoryStudyRepository();
    StudyRunService studyRunService = new StudyRunService(repository);

    AiServiceOperations ai = org.mockito.Mockito.mock(AiServiceOperations.class);
    org.springframework.http.HttpHeaders pngHeaders = new org.springframework.http.HttpHeaders();
    pngHeaders.setContentType(org.springframework.http.MediaType.IMAGE_PNG);
    byte[] pngBytes = {(byte) 0x89, 0x50, 0x4e, 0x47};
    org.springframework.http.ResponseEntity<byte[]> pngResponse =
        new org.springframework.http.ResponseEntity<>(
            pngBytes, pngHeaders, org.springframework.http.HttpStatus.OK);
    when(ai.getAsset(
            org.mockito.ArgumentMatchers.anyString(),
            org.mockito.ArgumentMatchers.eq("sagittal"),
            org.mockito.ArgumentMatchers.anyString()))
        .thenReturn(pngResponse);
    when(ai.getAsset(
            org.mockito.ArgumentMatchers.anyString(),
            org.mockito.ArgumentMatchers.eq("axial"),
            org.mockito.ArgumentMatchers.anyString()))
        .thenReturn(pngResponse);
    // The upstream mesh claims patient-specific anatomy — must be rejected, never stored.
    org.springframework.http.HttpHeaders jsonHeaders = new org.springframework.http.HttpHeaders();
    jsonHeaders.setContentType(org.springframework.http.MediaType.APPLICATION_JSON);
    byte[] unsafeMesh =
        """
            {"schemaVersion":"pfi.lumbar-geometric-proxy.v1","kind":"patient_specific_mesh","method":"dual_plane_bbox_proxy","anatomicalReconstruction":true,"volumetricReconstruction":false,"coordinateSystem":"local_proxy_space","units":"normalized"}
            """
            .getBytes(java.nio.charset.StandardCharsets.UTF_8);
    org.springframework.http.ResponseEntity<byte[]> unsafeMeshResponse =
        new org.springframework.http.ResponseEntity<>(
            unsafeMesh, jsonHeaders, org.springframework.http.HttpStatus.OK);
    when(ai.getAsset(
            org.mockito.ArgumentMatchers.anyString(),
            org.mockito.ArgumentMatchers.eq("workspace"),
            org.mockito.ArgumentMatchers.eq("lumbar-3d-mesh.json")))
        .thenReturn(unsafeMeshResponse);

    RunAssetContentStorage storage =
        new RunAssetContentStorage() {
          private final Map<String, ar.edu.uade.pfi.backend.domain.RunAssetContent> stored =
              new java.util.HashMap<>();

          @Override
          public ar.edu.uade.pfi.backend.domain.RunAssetContent store(
              ar.edu.uade.pfi.backend.domain.RunArtifact artifact, byte[] content, String sha256) {
            var value =
                new ar.edu.uade.pfi.backend.domain.RunAssetContent(
                    artifact.id(),
                    content,
                    sha256,
                    content.length,
                    "fake",
                    java.time.Instant.now());
            stored.put(artifact.id(), value);
            return value;
          }

          @Override
          public java.util.Optional<ar.edu.uade.pfi.backend.domain.RunAssetContent> find(
              String artifactId) {
            return java.util.Optional.ofNullable(stored.get(artifactId));
          }

          @Override
          public boolean exists(String artifactId) {
            return stored.containsKey(artifactId);
          }

          @Override
          public ar.edu.uade.pfi.backend.domain.RunAssetContent deleteOrReplace(
              ar.edu.uade.pfi.backend.domain.RunArtifact artifact, byte[] content, String sha256) {
            return store(artifact, content, sha256);
          }

          @Override
          public ar.edu.uade.pfi.backend.domain.RunAssetStorageDiagnostics diagnostics() {
            return new ar.edu.uade.pfi.backend.domain.RunAssetStorageDiagnostics(
                "fake", true, 0, 0, 0);
          }
        };
    RunAssetSnapshotService runAssetSnapshotService =
        new RunAssetSnapshotService(ai, repository, storage, null, 5_242_880);
    MultiplanarRunPersistenceService persistence =
        new MultiplanarRunPersistenceService(studyRunService, runAssetSnapshotService);

    MultiplanarRunRequestDto request =
        new MultiplanarRunRequestDto(
            "CASE-BE-MESH-ROLLBACK",
            "input-sag-mesh-rollback",
            "input-ax-mesh-rollback",
            null,
            null,
            "sagittal_spider",
            "axial_t2_alkafri",
            false,
            Map.of("inferenceMode", "real_baseline", "allowContractFallback", false));

    persistence.persistSuccessfulRun(request, response());

    StudyRun persisted = studyRunService.findRunByMultiplanarRunId("multi-be006").orElseThrow();
    Map<String, Object> snapshot = persisted.metricsSnapshot();
    Map<String, Object> threeD = (Map<String, Object>) snapshot.get("threeD");
    assertEquals(false, threeD.get("enabled"));
    assertEquals("experimental_blocked_insufficient_geometry", threeD.get("status"));
    assertTrue(((List<?>) threeD.get("assets")).isEmpty());

    // Sagittal/axial planes are not rolled back — only threeD is downgraded.
    Map<String, Object> planesSnapshot = (Map<String, Object>) snapshot.get("planes");
    assertTrue(planesSnapshot.containsKey("sagittal"));
    assertTrue(planesSnapshot.containsKey("axial"));

    ar.edu.uade.pfi.backend.domain.RunArtifact meshArtifact =
        persisted.artifacts().stream()
            .filter(artifact -> artifact.assetName().equals("lumbar-3d-mesh.json"))
            .findFirst()
            .orElseThrow();
    assertEquals("rejected", meshArtifact.storageStatus());
  }

  private Map<String, Object> asset(String planeRunId, String plane, String assetName) {
    Map<String, Object> asset = new LinkedHashMap<>();
    asset.put("assetName", assetName);
    asset.put("role", assetName.startsWith("mask") ? "mask_preview" : "preview");
    asset.put("contentType", "image/png");
    asset.put("generated", true);
    asset.put("relativePath", "/assets/" + planeRunId + "/" + plane + "/" + assetName);
    return asset;
  }

  private CanonicalMultiplanarRun response() {
    CanonicalPlaneRun sagittal =
        new CanonicalPlaneRun(
            "run-sag-be006",
            "sagittal",
            "completed",
            "real_baseline",
            false,
            null,
            Map.of(
                "modelKey", "sagittal_spider",
                "artifactHash", "sha256:sag-be006",
                "baselineReady", true,
                "readiness", "real_baseline_ready"),
            Map.of(
                "inputId",
                "input-sag-be006",
                "selectedSliceIndex",
                8,
                "sliceCount",
                17,
                "selectedAxis",
                2,
                "inPlaneSpacingMm",
                List.of(0.7, 0.7)),
            Map.of(),
            List.of(Map.of("label", "canal_lumbar")),
            List.of(asset("run-sag-be006", "sagittal", "overlay.png")),
            List.of(),
            List.of(Map.of("name", "L4_left_pedicle")),
            List.of(Map.of("id", "canalAreaMm2", "value", 82.4)),
            Map.of("quality", 0.94));
    CanonicalPlaneRun axial =
        new CanonicalPlaneRun(
            "run-ax-be006",
            "axial",
            "completed",
            "real_baseline",
            false,
            null,
            Map.of(
                "modelKey", "axial_t2_alkafri",
                "artifactHash", "sha256:ax-be006",
                "trainingStatus", "candidate_below_quality_gate",
                "baselineReady", false,
                "availableForRealInference", true,
                "readiness", "real_candidate_ready",
                "runtimeQualification", "axial_candidate_runtime_ready",
                "qualityGatePassed", false),
            Map.of(
                "inputId",
                "input-ax-be006",
                "selectedSliceIndex",
                2,
                "sliceCount",
                12,
                "selectedAxis",
                0),
            Map.of(),
            List.of(Map.of("label", "estenosis")),
            List.of(asset("run-ax-be006", "axial", "mask-preview.png")),
            List.of(),
            List.of(Map.of("name", "canal_center")),
            List.of(Map.of("id", "leftForamenMm", "value", 3.1)),
            Map.of("quality", 0.88));
    Map<String, CanonicalPlaneRun> planes = new LinkedHashMap<>();
    planes.put("sagittal", sagittal);
    planes.put("axial", axial);
    return new CanonicalMultiplanarRun(
        "multiplanar_run_ready",
        "multiplanar-run-v1",
        "multi-be006",
        "trace-be006",
        "CASE-BE-006",
        "dual_plane_with_3d_context",
        "real_baseline",
        "real_baseline",
        List.of("sagittal", "axial"),
        List.of("sagittal", "axial"),
        false,
        null,
        Map.of(),
        planes,
        threeD("multi-be006", "run-sag-be006", "run-ax-be006"),
        Map.of(),
        Map.of("status", "pendiente"),
        new CanonicalMultiplanarRun.Governance(true, true, true, false));
  }

  private Map<String, Object> threeD(String runId, String sagittalRunId, String axialRunId) {
    return Map.of(
        "enabled", true,
        "status", "experimental_ready",
        "sourcePlaneRunIds", Map.of("sagittal", sagittalRunId, "axial", axialRunId),
        "requiredInputs", List.of("sagittal_masks", "axial_masks", "explicit_anatomical_mapping"),
        "assets",
            List.of(
                Map.of(
                    "assetName", "lumbar-3d-mesh.json",
                    "role", "mesh_3d",
                    "contentType", "application/json",
                    "generated", true,
                    "relativePath", "/assets/" + runId + "/workspace/lumbar-3d-mesh.json")),
        "reconstruction",
            Map.of(
                "kind", "experimental_geometric_proxy",
                "method", "dual_plane_bbox_proxy",
                "anatomicalReconstruction", false,
                "volumetricReconstruction", false,
                "coordinateSystem", "local_proxy_space",
                "units", "normalized",
                "mappingSource", "config",
                "mappingValidated", false),
        "warnings", List.of("experimental_proxy_not_clinical_3d"));
  }

  private CanonicalMultiplanarRun sagittalOnlyResponse() {
    CanonicalPlaneRun sagittal =
        new CanonicalPlaneRun(
            "run-sag-only",
            "sagittal",
            "completed",
            "real_baseline",
            false,
            null,
            Map.of(
                "modelKey", "sagittal_spider",
                "modelVersion", "sagittal-spider-final-v1",
                "artifactHash", MultiplanarRealBaselineContractValidator.SAGITTAL_ARTIFACT_HASH),
            Map.of(
                "inputId",
                "input-sag-only",
                "nativeShape",
                List.of(352, 384, 17),
                "canonicalShape",
                List.of(352, 384, 17),
                "selectedSliceIndex",
                8),
            Map.of("width", 256, "height", 256),
            List.of(Map.of("label", "sagittal")),
            List.of(
                asset("run-sag-only", "sagittal", "input.png"),
                asset("run-sag-only", "sagittal", "overlay.png")),
            List.of(),
            List.of(Map.of("name", "L4_left_pedicle")),
            List.of(Map.of("id", "canalAreaMm2", "value", 82.4)),
            Map.of("confidence", 0.94));
    Map<String, CanonicalPlaneRun> planes = new LinkedHashMap<>();
    planes.put("sagittal", sagittal);
    return new CanonicalMultiplanarRun(
        "multiplanar_run_ready",
        "multiplanar-run-v1",
        "multi-sag-only",
        "trace-sag-only",
        "CASE-BE-SAG-ONLY",
        "sagittal_only_with_optional_axial",
        "real_baseline",
        "mixed",
        List.of("sagittal"),
        List.of("sagittal"),
        false,
        null,
        Map.of("sagittalRunReady", true, "axialRunReady", false, "dualRunReady", false),
        planes,
        Map.of(),
        Map.of(),
        Map.of("status", "pending"),
        new CanonicalMultiplanarRun.Governance(true, true, true, false));
  }
}
