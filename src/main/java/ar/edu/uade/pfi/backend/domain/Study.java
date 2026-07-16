package ar.edu.uade.pfi.backend.domain;

import java.time.Instant;
import java.util.Objects;

public record Study(
    String id,
    String caseId,
    String status,
    Instant createdAt,
    Instant updatedAt
) {
    public Study {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(caseId, "caseId");
        Objects.requireNonNull(status, "status");
        Objects.requireNonNull(createdAt, "createdAt");
        Objects.requireNonNull(updatedAt, "updatedAt");
    }
}
