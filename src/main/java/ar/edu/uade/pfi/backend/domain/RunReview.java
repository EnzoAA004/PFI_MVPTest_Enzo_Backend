package ar.edu.uade.pfi.backend.domain;

import java.time.Instant;
import java.util.List;
import java.util.Objects;

public record RunReview(
    String multiplanarRunId,
    String traceId,
    String reviewStatus,
    String reviewer,
    Instant reviewedAt,
    String comments,
    List<MeasurementCorrection> corrections
) {
    public RunReview {
        Objects.requireNonNull(multiplanarRunId, "multiplanarRunId");
        Objects.requireNonNull(traceId, "traceId");
        Objects.requireNonNull(reviewStatus, "reviewStatus");
        Objects.requireNonNull(reviewer, "reviewer");
        Objects.requireNonNull(comments, "comments");
        Objects.requireNonNull(corrections, "corrections");
        corrections = List.copyOf(corrections);
    }
}
