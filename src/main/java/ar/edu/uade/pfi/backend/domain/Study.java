package ar.edu.uade.pfi.backend.domain;

import java.time.Instant;
import java.time.LocalDate;
import java.util.Objects;

public record Study(
    String id,
    String caseId,
    String status,
    String subjectRef,
    LocalDate studyDate,
    String modality,
    String description,
    String reviewPriority,
    Instant createdAt,
    Instant updatedAt) {
  public Study(String id, String caseId, String status, Instant createdAt, Instant updatedAt) {
    this(id, caseId, status, null, null, null, null, "medium", createdAt, updatedAt);
  }

  public Study {
    Objects.requireNonNull(id, "id");
    Objects.requireNonNull(caseId, "caseId");
    Objects.requireNonNull(status, "status");
    if (reviewPriority == null || reviewPriority.isBlank()) reviewPriority = "medium";
    Objects.requireNonNull(createdAt, "createdAt");
    Objects.requireNonNull(updatedAt, "updatedAt");
  }
}
