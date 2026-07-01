package ar.edu.uade.pfi.backend.dto;

public record MeasurementSaveDto(
    String id,
    String label,
    Object value,
    String unit,
    Double confidence,
    String plane,
    String source,
    String status,
    Boolean outlier,
    Boolean placeholder
) {}
