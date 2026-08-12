package ar.edu.uade.pfi.backend.domain;

import java.time.Instant;
import java.util.Objects;

/** De-identified longitudinal patient identity. Direct patient identifiers are out of scope. */
public record Patient(String id, String patientReference, Instant createdAt, Instant updatedAt) {
  public Patient {
    Objects.requireNonNull(id, "id");
    Objects.requireNonNull(patientReference, "patientReference");
    patientReference = patientReference.trim();
    if (patientReference.isEmpty()) throw new IllegalArgumentException("patientReference is blank");
    Objects.requireNonNull(createdAt, "createdAt");
    Objects.requireNonNull(updatedAt, "updatedAt");
  }
}
