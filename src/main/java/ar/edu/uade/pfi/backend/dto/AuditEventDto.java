package ar.edu.uade.pfi.backend.dto;

import java.time.Instant;

public record AuditEventDto(
    String id,
    Instant timestamp,
    String reviewer,
    String action,
    String detail
) {}
