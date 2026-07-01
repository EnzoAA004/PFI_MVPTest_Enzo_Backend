package ar.edu.uade.pfi.backend.dto;

import java.util.Map;

public record MeasurementDto(
    String name,
    Double value,
    String unit,
    Map<String, Object> metadata
) {}
