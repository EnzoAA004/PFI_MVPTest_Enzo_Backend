package ar.edu.uade.pfi.backend.service;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ar.edu.uade.pfi.backend.domain.RunArtifact;
import ar.edu.uade.pfi.backend.domain.Study;
import ar.edu.uade.pfi.backend.domain.StudyRun;
import ar.edu.uade.pfi.backend.repository.PostgresStudyRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.security.MessageDigest;
import java.sql.DriverManager;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers
class PostgresRunAssetContentStorageTest {
  @Container
  static final PostgreSQLContainer<?> postgres =
      new PostgreSQLContainer<>("postgres:16-alpine")
          .withDatabaseName("pfi_p8c1_assets")
          .withUsername("pfi")
          .withPassword("pfi");

  @Test
  void storesReplacesAndFindsPngPayloadByPersistedArtifactId() throws Exception {
    PostgresStudyRepository repository = repository();
    PostgresRunAssetContentStorage storage = storage();
    Instant now = Instant.parse("2026-07-26T12:00:00Z");
    Study study =
        repository.saveStudy(
            new Study(UUID.randomUUID().toString(), "CASE-P8C1-STORAGE", "ready", now, now));
    String requestedRunId = UUID.randomUUID().toString();
    RunArtifact requestedArtifact =
        new RunArtifact(
            UUID.randomUUID().toString(),
            requestedRunId,
            "run-sag-p8c1-storage",
            "sagittal",
            "overlay.png",
            "image/png",
            "overlay.png",
            now);

    StudyRun persisted =
        repository.saveRun(
            run(
                requestedRunId,
                study.id(),
                "multi-p8c1-storage",
                "trace-p8c1-storage",
                List.of(requestedArtifact),
                now));
    RunArtifact persistedArtifact = persisted.artifacts().get(0);
    byte[] first = png(1);
    byte[] second = png(2);

    storage.store(persistedArtifact, first, sha256(first));
    RunArtifact stored =
        repository.updateArtifactStorage(
            persistedArtifact.id(), "stored", "postgres_bytea", (long) first.length, sha256(first));
    storage.deleteOrReplace(stored, second, sha256(second));
    RunArtifact replaced =
        repository.updateArtifactStorage(
            stored.id(), "stored", "postgres_bytea", (long) second.length, sha256(second));

    assertEquals(persisted.id(), replaced.studyRunId());
    assertArrayEquals(second, storage.find(replaced.id()).orElseThrow().content());
    assertEquals(1, countPayloadRows(replaced.id()));
    assertEquals(1, storage.diagnostics().storedAssetCount());
    assertTrue(
        repository
            .findArtifactByRunPlaneAndName("run-sag-p8c1-storage", "sagittal", "overlay.png")
            .orElseThrow()
            .available());
  }

  @Test
  void rejectsRawOrNonPngPayloads() throws Exception {
    RunArtifact raw =
        new RunArtifact(
            UUID.randomUUID().toString(),
            UUID.randomUUID().toString(),
            "run-sag-raw",
            "sagittal",
            "mask.npy",
            "application/octet-stream",
            "mask.npy",
            Instant.parse("2026-07-26T12:30:00Z"));

    assertThrows(
        IllegalArgumentException.class, () -> storage().store(raw, new byte[] {1, 2, 3}, "abc"));
  }

  @Test
  void storesWorkspaceLumbarMeshJsonPayload() throws Exception {
    PostgresStudyRepository repository = repository();
    PostgresRunAssetContentStorage storage = storage();
    Instant now = Instant.parse("2026-07-26T12:45:00Z");
    Study study =
        repository.saveStudy(
            new Study(UUID.randomUUID().toString(), "CASE-P9B2-MESH", "ready", now, now));
    RunArtifact requestedArtifact =
        new RunArtifact(
            UUID.randomUUID().toString(),
            UUID.randomUUID().toString(),
            "multi-p9b2-mesh",
            "workspace",
            "lumbar-3d-mesh.json",
            "application/json",
            "lumbar-3d-mesh.json",
            now);
    StudyRun persisted =
        repository.saveRun(
            run(
                UUID.randomUUID().toString(),
                study.id(),
                "multi-p9b2-mesh",
                "trace-p9b2-mesh",
                List.of(requestedArtifact),
                now));
    RunArtifact persistedArtifact = persisted.artifacts().get(0);
    byte[] json = meshJson();

    storage.store(persistedArtifact, json, sha256(json));
    RunArtifact stored =
        repository.updateArtifactStorage(
            persistedArtifact.id(), "stored", "postgres_bytea", (long) json.length, sha256(json));

    assertArrayEquals(json, storage.find(stored.id()).orElseThrow().content());
    assertEquals("application/json", stored.contentType());
  }

  @Test
  void rejectsMeshJsonThatClaimsAnatomicalOrVolumetricReconstruction() throws Exception {
    RunArtifact mesh =
        new RunArtifact(
            UUID.randomUUID().toString(),
            UUID.randomUUID().toString(),
            "multi-unsafe-mesh",
            "workspace",
            "lumbar-3d-mesh.json",
            "application/json",
            "lumbar-3d-mesh.json",
            Instant.parse("2026-07-26T13:00:00Z"));
    byte[] unsafe =
        """
            {"schemaVersion":"pfi.lumbar-geometric-proxy.v1","kind":"experimental_geometric_proxy","method":"dual_plane_bbox_proxy","anatomicalReconstruction":true,"volumetricReconstruction":false,"coordinateSystem":"local_proxy_space","units":"normalized"}
            """
            .getBytes(java.nio.charset.StandardCharsets.UTF_8);

    assertThrows(
        IllegalArgumentException.class, () -> storage().store(mesh, unsafe, sha256(unsafe)));
  }

  private PostgresStudyRepository repository() {
    return new PostgresStudyRepository(new ObjectMapper(), jdbcUrl(), true);
  }

  private PostgresRunAssetContentStorage storage() {
    return new PostgresRunAssetContentStorage(jdbcUrl(), 5L * 1024L * 1024L, true);
  }

  private StudyRun run(
      String id,
      String studyId,
      String multiplanarRunId,
      String traceId,
      List<RunArtifact> artifacts,
      Instant now) {
    return new StudyRun(
        id,
        studyId,
        multiplanarRunId,
        traceId,
        "real_baseline",
        "real_baseline",
        "sagittal_spider",
        "",
        "sha256:sag",
        "",
        "run-sag-p8c1-storage",
        "",
        Map.of("sagittal", Map.of("overlay.png", "overlay.png")),
        Map.of("humanReviewRequired", true, "notClinicalDiagnosis", true),
        artifacts,
        "completed",
        "pending",
        "",
        null,
        "",
        now,
        now);
  }

  private int countPayloadRows(String artifactId) throws Exception {
    try (var connection =
            DriverManager.getConnection(
                postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
        var statement =
            connection.prepareStatement(
                "SELECT count(*) FROM domain_run_asset_payloads WHERE artifact_id = ?::uuid")) {
      statement.setString(1, artifactId);
      try (var rs = statement.executeQuery()) {
        rs.next();
        return rs.getInt(1);
      }
    }
  }

  private String jdbcUrl() {
    return postgres.getJdbcUrl()
        + "&user="
        + postgres.getUsername()
        + "&password="
        + postgres.getPassword();
  }

  private byte[] png(int marker) {
    return new byte[] {(byte) 0x89, 0x50, 0x4e, 0x47, (byte) marker};
  }

  private byte[] meshJson() {
    return """
            {
              "schemaVersion": "pfi.lumbar-geometric-proxy.v1",
              "kind": "experimental_geometric_proxy",
              "method": "dual_plane_bbox_proxy",
              "anatomicalReconstruction": false,
              "volumetricReconstruction": false,
              "coordinateSystem": "local_proxy_space",
              "units": "normalized"
            }
            """
        .getBytes(java.nio.charset.StandardCharsets.UTF_8);
  }

  private String sha256(byte[] value) throws Exception {
    return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value));
  }
}
