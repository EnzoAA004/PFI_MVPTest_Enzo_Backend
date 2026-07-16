package ar.edu.uade.pfi.backend.domain;

import java.time.Instant;
import java.util.Map;
import java.util.Objects;

public record MeasurementCorrection(
    String id,
    String studyRunId,
    String measurementId,
    String label,
    Map<String, Object> beforeValue,
    Map<String, Object> afterValue,
    String comment,
    Instant createdAt
) {
    public MeasurementCorrection {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(studyRunId, "studyRunId");
        Objects.requireNonNull(measurementId, "measurementId");
        Objects.requireNonNull(label, "label");
        Objects.requireNonNull(beforeValue, "beforeValue");
        Objects.requireNonNull(afterValue, "afterValue");
        Objects.requireNonNull(comment, "comment");
        Objects.requireNonNull(createdAt, "createdAt");
        beforeValue = Map.copyOf(beforeValue);
        afterValue = Map.copyOf(afterValue);
    }
}
