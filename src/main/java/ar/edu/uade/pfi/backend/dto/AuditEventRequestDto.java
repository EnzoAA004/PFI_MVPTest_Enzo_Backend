package ar.edu.uade.pfi.backend.dto;

public record AuditEventRequestDto(
    String reviewer,
    String action,
    String detail
) {}
