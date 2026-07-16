package ar.edu.uade.pfi.backend.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.Map;

@JsonIgnoreProperties(ignoreUnknown = true)
public record MeasurementCorrectionDto(
    String measurementId,
    String label,
    Map<String, Object> beforeValue,
    Map<String, Object> afterValue,
    String comment
) {}
