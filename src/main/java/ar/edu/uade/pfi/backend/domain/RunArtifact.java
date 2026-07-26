package ar.edu.uade.pfi.backend.domain;

import java.time.Instant;
import java.util.Objects;

public record RunArtifact(
    String id,
    String studyRunId,
    String runId,
    String plane,
    String assetName,
    String contentType,
    String artifactRef,
    Instant createdAt,
    String storageStatus,
    String storageKind,
    Long sizeBytes,
    String sha256
) {
    public RunArtifact(
        String id,
        String studyRunId,
        String runId,
        String plane,
        String assetName,
        String contentType,
        String artifactRef,
        Instant createdAt
    ) {
        this(id, studyRunId, runId, plane, assetName, contentType, artifactRef, createdAt, "upstream_only", null, null, null);
    }

    public RunArtifact {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(studyRunId, "studyRunId");
        Objects.requireNonNull(runId, "runId");
        Objects.requireNonNull(plane, "plane");
        Objects.requireNonNull(assetName, "assetName");
        Objects.requireNonNull(contentType, "contentType");
        Objects.requireNonNull(artifactRef, "artifactRef");
        Objects.requireNonNull(createdAt, "createdAt");
        storageStatus = storageStatus == null || storageStatus.isBlank() ? "upstream_only" : storageStatus;
    }

    public boolean available() {
        return "stored".equals(storageStatus) && sizeBytes != null && sizeBytes > 0 && sha256 != null && !sha256.isBlank();
    }
}
