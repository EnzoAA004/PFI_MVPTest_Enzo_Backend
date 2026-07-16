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
    Instant createdAt
) {
    public RunArtifact {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(studyRunId, "studyRunId");
        Objects.requireNonNull(runId, "runId");
        Objects.requireNonNull(plane, "plane");
        Objects.requireNonNull(assetName, "assetName");
        Objects.requireNonNull(contentType, "contentType");
        Objects.requireNonNull(artifactRef, "artifactRef");
        Objects.requireNonNull(createdAt, "createdAt");
    }
}
