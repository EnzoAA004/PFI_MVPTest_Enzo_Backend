package ar.edu.uade.pfi.backend.dto;

import java.time.Instant;
import java.util.Map;

public record AuditEventResponseDto(
    String id,
    String actor,
    String action,
    String entityId,
    String traceId,
    Instant timestamp,
    Map<String, Object> metadata
) {}
