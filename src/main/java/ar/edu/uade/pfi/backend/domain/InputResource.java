package ar.edu.uade.pfi.backend.domain;

import java.time.Instant;
import java.util.Objects;

public record InputResource(
    String id,
    String studyId,
    String plane,
    String inputId,
    String format,
    long size,
    Instant createdAt
) {
    public InputResource {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(studyId, "studyId");
        Objects.requireNonNull(plane, "plane");
        Objects.requireNonNull(inputId, "inputId");
        Objects.requireNonNull(format, "format");
        Objects.requireNonNull(createdAt, "createdAt");
        if (size < 0) throw new IllegalArgumentException("size must be non-negative");
    }
}
