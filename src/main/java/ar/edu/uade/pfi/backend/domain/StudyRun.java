package ar.edu.uade.pfi.backend.domain;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;

public record StudyRun(
    String id,
    String studyId,
    String multiplanarRunId,
    String traceId,
    String requestedInferenceMode,
    String effectiveInferenceMode,
    String sagittalModelKey,
    String axialModelKey,
    String sagittalArtifactHash,
    String axialArtifactHash,
    String sagittalRunId,
    String axialRunId,
    Map<String, Object> assets,
    Map<String, Object> metricsSnapshot,
    List<RunArtifact> artifacts,
    String status,
    String reviewStatus,
    String reviewer,
    Instant reviewedAt,
    String comments,
    Instant createdAt,
    Instant updatedAt
) {
    public StudyRun {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(studyId, "studyId");
        Objects.requireNonNull(multiplanarRunId, "multiplanarRunId");
        Objects.requireNonNull(assets, "assets");
        Objects.requireNonNull(metricsSnapshot, "metricsSnapshot");
        Objects.requireNonNull(artifacts, "artifacts");
        Objects.requireNonNull(status, "status");
        Objects.requireNonNull(reviewStatus, "reviewStatus");
        Objects.requireNonNull(reviewer, "reviewer");
        Objects.requireNonNull(comments, "comments");
        Objects.requireNonNull(createdAt, "createdAt");
        Objects.requireNonNull(updatedAt, "updatedAt");
        assets = Map.copyOf(assets);
        metricsSnapshot = Map.copyOf(metricsSnapshot);
        artifacts = List.copyOf(artifacts);
    }
}
