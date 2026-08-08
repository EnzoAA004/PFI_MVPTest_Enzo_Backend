package ar.edu.uade.pfi.backend.service;

import ar.edu.uade.pfi.backend.client.AiServiceOperations;
import ar.edu.uade.pfi.backend.domain.RunArtifact;
import ar.edu.uade.pfi.backend.domain.StudyRun;
import ar.edu.uade.pfi.backend.repository.StudyRepository;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Map;
import java.util.Set;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

@Service
public class RunAssetSnapshotService {
  private static final Set<String> PUBLIC_ASSETS =
      Set.of("input.png", "overlay.png", "mask-preview.png", "lumbar-3d-mesh.json");
  private static final String PNG_CONTENT_TYPE = "image/png";
  private static final String JSON_CONTENT_TYPE = "application/json";
  private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

  private final AiServiceOperations aiServiceClient;
  private final StudyRepository repository;
  private final RunAssetContentStorage storage;
  private final AuditService auditService;
  private final long maxBytes;

  @Autowired
  public RunAssetSnapshotService(
      AiServiceOperations aiServiceClient,
      StudyRepository repository,
      ObjectProvider<RunAssetContentStorage> storageProvider,
      ObjectProvider<AuditService> auditServiceProvider,
      @Value("${pfi.asset-storage.max-bytes:5242880}") long maxBytes) {
    this(
        aiServiceClient,
        repository,
        storageProvider.getIfAvailable(),
        auditServiceProvider.getIfAvailable(),
        maxBytes);
  }

  public RunAssetSnapshotService(
      AiServiceOperations aiServiceClient,
      StudyRepository repository,
      RunAssetContentStorage storage,
      AuditService auditService,
      long maxBytes) {
    this.aiServiceClient = aiServiceClient;
    this.repository = repository;
    this.storage = storage;
    this.auditService = auditService;
    this.maxBytes = maxBytes;
  }

  /**
   * Downloads, validates and stores every public artifact of the run. Returns false only when the
   * workspace 3D mesh was present among the run's artifacts and ended up in any state other than
   * "stored" — the caller (MultiplanarRunPersistenceService) uses this to downgrade an
   * already-persisted threeD.enabled=true snapshot to a blocked state, so the mesh URL is never
   * published pointing at unavailable content.
   */
  public boolean snapshot(StudyRun run) {
    if (run == null || storage == null || run.artifacts() == null) return true;
    boolean meshAvailable = true;
    for (RunArtifact artifact : run.artifacts()) {
      RunArtifact result = snapshot(artifact, run.traceId());
      if ("lumbar-3d-mesh.json".equals(artifact.assetName())
          && !"stored".equals(result.storageStatus())) {
        meshAvailable = false;
      }
    }
    return meshAvailable;
  }

  public RunArtifact backfill(RunArtifact artifact, String traceId) {
    if (storage == null) return artifact;
    return snapshot(artifact, traceId);
  }

  private RunArtifact snapshot(RunArtifact artifact, String traceId) {
    if (!isPublicAsset(artifact)) {
      RunArtifact rejected =
          repository.updateArtifactStorage(artifact.id(), "rejected", null, null, null);
      audit("asset.snapshot.rejected", artifact, traceId, Map.of("reason", "not_public_asset"));
      return rejected;
    }
    try {
      ResponseEntity<byte[]> upstream =
          aiServiceClient.getAsset(artifact.runId(), artifact.plane(), artifact.assetName());
      byte[] body = upstream.getBody();
      if (upstream.getStatusCode().value() == HttpStatus.NOT_FOUND.value()) {
        return missing(artifact, traceId);
      }
      validateResponse(artifact, upstream, body);
      String sha256 = sha256(body);
      storage.deleteOrReplace(artifact, body, sha256);
      RunArtifact stored =
          repository.updateArtifactStorage(
              artifact.id(),
              "stored",
              PostgresRunAssetContentStorage.STORAGE_KIND,
              (long) body.length,
              sha256);
      audit(
          "asset.snapshot.stored",
          artifact,
          traceId,
          Map.of("sizeBytes", body.length, "sha256", sha256));
      return stored;
    } catch (ResponseStatusException ex) {
      if (ex.getStatusCode().value() == HttpStatus.NOT_FOUND.value()) {
        return missing(artifact, traceId);
      }
      RunArtifact rejected =
          repository.updateArtifactStorage(artifact.id(), "rejected", null, null, null);
      audit(
          "asset.snapshot.rejected",
          artifact,
          traceId,
          Map.of("status", ex.getStatusCode().value()));
      return rejected;
    } catch (RuntimeException ex) {
      RunArtifact rejected =
          repository.updateArtifactStorage(artifact.id(), "rejected", null, null, null);
      audit(
          "asset.snapshot.rejected",
          artifact,
          traceId,
          Map.of("reason", "validation_or_storage_error"));
      return rejected;
    }
  }

  private RunArtifact missing(RunArtifact artifact, String traceId) {
    RunArtifact missing =
        repository.updateArtifactStorage(artifact.id(), "missing", null, null, null);
    audit("asset.snapshot.missing", artifact, traceId, Map.of("status", 404));
    return missing;
  }

  private void validateResponse(
      RunArtifact artifact, ResponseEntity<byte[]> response, byte[] body) {
    if (!response.getStatusCode().is2xxSuccessful())
      throw new IllegalArgumentException("Asset upstream status is not successful");
    String contentType = response.getHeaders().getFirst(HttpHeaders.CONTENT_TYPE);
    if (contentType == null || !isExpectedContentType(artifact, contentType)) {
      throw new IllegalArgumentException("Asset content type is not allowed");
    }
    if (body == null || body.length == 0)
      throw new IllegalArgumentException("Asset payload cannot be empty");
    if (body.length > maxBytes)
      throw new IllegalArgumentException("Asset payload exceeds max size");
    validatePayloadContract(artifact, body);
  }

  private boolean isPublicAsset(RunArtifact artifact) {
    if (artifact == null || !PUBLIC_ASSETS.contains(artifact.assetName())) return false;
    if (artifact.assetName().contains("/")
        || artifact.assetName().contains("\\")
        || artifact.assetName().contains("..")) return false;
    if ("lumbar-3d-mesh.json".equals(artifact.assetName())) {
      return "workspace".equals(artifact.plane())
          && JSON_CONTENT_TYPE.equalsIgnoreCase(artifact.contentType());
    }
    return PNG_CONTENT_TYPE.equalsIgnoreCase(artifact.contentType());
  }

  private boolean isExpectedContentType(RunArtifact artifact, String contentType) {
    String normalized = contentType.toLowerCase();
    if ("lumbar-3d-mesh.json".equals(artifact.assetName()))
      return normalized.startsWith(JSON_CONTENT_TYPE);
    return normalized.startsWith(PNG_CONTENT_TYPE);
  }

  private void validatePayloadContract(RunArtifact artifact, byte[] body) {
    if ("lumbar-3d-mesh.json".equals(artifact.assetName())) {
      validateMeshJson(body);
      return;
    }
    if (body.length < 4
        || (body[0] & 0xff) != 0x89
        || body[1] != 0x50
        || body[2] != 0x4e
        || body[3] != 0x47) {
      throw new IllegalArgumentException("PNG asset payload is invalid");
    }
  }

  private void validateMeshJson(byte[] body) {
    try {
      Map<String, Object> mesh = OBJECT_MAPPER.readValue(body, new TypeReference<>() {});
      requireText(mesh, "schemaVersion", "pfi.lumbar-geometric-proxy.v1");
      requireText(mesh, "kind", "experimental_geometric_proxy");
      requireText(mesh, "method", "dual_plane_bbox_proxy");
      requireBoolean(mesh, "anatomicalReconstruction", false);
      requireBoolean(mesh, "volumetricReconstruction", false);
      requireText(mesh, "coordinateSystem", "local_proxy_space");
      requireText(mesh, "units", "normalized");
    } catch (Exception ex) {
      throw new IllegalArgumentException("Mesh JSON asset payload is invalid", ex);
    }
  }

  private void requireText(Map<String, Object> mesh, String key, String expected) {
    if (!expected.equals(mesh.get(key))) {
      throw new IllegalArgumentException("Mesh JSON contract mismatch");
    }
  }

  private void requireBoolean(Map<String, Object> mesh, String key, boolean expected) {
    if (!Boolean.valueOf(expected).equals(mesh.get(key))) {
      throw new IllegalArgumentException("Mesh JSON contract mismatch");
    }
  }

  private String sha256(byte[] body) {
    try {
      MessageDigest digest = MessageDigest.getInstance("SHA-256");
      return HexFormat.of().formatHex(digest.digest(body));
    } catch (Exception ex) {
      throw new IllegalStateException("Could not calculate asset sha256", ex);
    }
  }

  private void audit(
      String action, RunArtifact artifact, String traceId, Map<String, Object> metadata) {
    if (auditService == null) return;
    try {
      auditService.record(
          "backend", action, artifact.runId(), traceId == null ? "" : traceId, metadata);
    } catch (RuntimeException ignored) {
      // Asset audit must never mask run persistence.
    }
  }
}
