package ar.edu.uade.pfi.backend.service;

import ar.edu.uade.pfi.backend.client.AiServiceOperations;
import ar.edu.uade.pfi.backend.domain.RunArtifact;
import ar.edu.uade.pfi.backend.domain.StudyRun;
import ar.edu.uade.pfi.backend.repository.StudyRepository;
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
    private static final Set<String> PUBLIC_ASSETS = Set.of("input.png", "overlay.png", "mask-preview.png");
    private static final String PNG_CONTENT_TYPE = "image/png";

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
        @Value("${pfi.asset-storage.max-bytes:5242880}") long maxBytes
    ) {
        this(aiServiceClient, repository, storageProvider.getIfAvailable(), auditServiceProvider.getIfAvailable(), maxBytes);
    }

    public RunAssetSnapshotService(
        AiServiceOperations aiServiceClient,
        StudyRepository repository,
        RunAssetContentStorage storage,
        AuditService auditService,
        long maxBytes
    ) {
        this.aiServiceClient = aiServiceClient;
        this.repository = repository;
        this.storage = storage;
        this.auditService = auditService;
        this.maxBytes = maxBytes;
    }

    public void snapshot(StudyRun run) {
        if (run == null || storage == null || run.artifacts() == null) return;
        for (RunArtifact artifact : run.artifacts()) {
            snapshot(artifact, run.traceId());
        }
    }

    public RunArtifact backfill(RunArtifact artifact, String traceId) {
        if (storage == null) return artifact;
        return snapshot(artifact, traceId);
    }

    private RunArtifact snapshot(RunArtifact artifact, String traceId) {
        if (!isPublicPng(artifact)) {
            RunArtifact rejected = repository.updateArtifactStorage(artifact.id(), "rejected", null, null, null);
            audit("asset.snapshot.rejected", artifact, traceId, Map.of("reason", "not_public_png"));
            return rejected;
        }
        try {
            ResponseEntity<byte[]> upstream = aiServiceClient.getAsset(artifact.runId(), artifact.plane(), artifact.assetName());
            byte[] body = upstream.getBody();
            if (upstream.getStatusCode().value() == HttpStatus.NOT_FOUND.value()) {
                return missing(artifact, traceId);
            }
            validateResponse(upstream, body);
            String sha256 = sha256(body);
            storage.deleteOrReplace(artifact, body, sha256);
            RunArtifact stored = repository.updateArtifactStorage(artifact.id(), "stored", PostgresRunAssetContentStorage.STORAGE_KIND, (long) body.length, sha256);
            audit("asset.snapshot.stored", artifact, traceId, Map.of("sizeBytes", body.length, "sha256", sha256));
            return stored;
        } catch (ResponseStatusException ex) {
            if (ex.getStatusCode().value() == HttpStatus.NOT_FOUND.value()) {
                return missing(artifact, traceId);
            }
            RunArtifact rejected = repository.updateArtifactStorage(artifact.id(), "rejected", null, null, null);
            audit("asset.snapshot.rejected", artifact, traceId, Map.of("status", ex.getStatusCode().value()));
            return rejected;
        } catch (RuntimeException ex) {
            RunArtifact rejected = repository.updateArtifactStorage(artifact.id(), "rejected", null, null, null);
            audit("asset.snapshot.rejected", artifact, traceId, Map.of("reason", "validation_or_storage_error"));
            return rejected;
        }
    }

    private RunArtifact missing(RunArtifact artifact, String traceId) {
        RunArtifact missing = repository.updateArtifactStorage(artifact.id(), "missing", null, null, null);
        audit("asset.snapshot.missing", artifact, traceId, Map.of("status", 404));
        return missing;
    }

    private void validateResponse(ResponseEntity<byte[]> response, byte[] body) {
        if (!response.getStatusCode().is2xxSuccessful()) throw new IllegalArgumentException("Asset upstream status is not successful");
        String contentType = response.getHeaders().getFirst(HttpHeaders.CONTENT_TYPE);
        if (contentType == null || !contentType.toLowerCase().startsWith(PNG_CONTENT_TYPE)) {
            throw new IllegalArgumentException("Only image/png assets can be stored");
        }
        if (body == null || body.length == 0) throw new IllegalArgumentException("Asset payload cannot be empty");
        if (body.length > maxBytes) throw new IllegalArgumentException("Asset payload exceeds max size");
    }

    private boolean isPublicPng(RunArtifact artifact) {
        return artifact != null
            && PUBLIC_ASSETS.contains(artifact.assetName())
            && PNG_CONTENT_TYPE.equalsIgnoreCase(artifact.contentType())
            && !artifact.assetName().contains("/")
            && !artifact.assetName().contains("\\")
            && !artifact.assetName().contains("..");
    }

    private String sha256(byte[] body) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(body));
        } catch (Exception ex) {
            throw new IllegalStateException("Could not calculate asset sha256", ex);
        }
    }

    private void audit(String action, RunArtifact artifact, String traceId, Map<String, Object> metadata) {
        if (auditService == null) return;
        try {
            auditService.record("backend", action, artifact.runId(), traceId == null ? "" : traceId, metadata);
        } catch (RuntimeException ignored) {
            // Asset audit must never mask run persistence.
        }
    }
}
