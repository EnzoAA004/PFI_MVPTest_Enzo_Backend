package ar.edu.uade.pfi.backend.dto;

import java.time.Instant;

public record PatientSummaryDto(
    String id, String patientReference, Instant createdAt, Instant updatedAt) {}
