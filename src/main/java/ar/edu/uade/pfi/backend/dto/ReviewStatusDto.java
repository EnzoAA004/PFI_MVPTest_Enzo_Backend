package ar.edu.uade.pfi.backend.dto;

import java.time.Instant;

public record ReviewStatusDto(
    String runId,
    String status,
    String notes,
    String reviewer,
    Instant updatedAt
) {}
