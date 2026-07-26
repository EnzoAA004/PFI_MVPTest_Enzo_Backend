package ar.edu.uade.pfi.backend.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.List;

@JsonIgnoreProperties(ignoreUnknown = false)
public record AiPlaneMeasurementV2Dto(
    String id,
    String labelKey,
    Double value,
    String unit,
    Double confidence,
    String source,
    String status,
    String plane,
    String level,
    String measurementBasis,
    List<String> linkedLandmarkIds
) {}
