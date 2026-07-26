package ar.edu.uade.pfi.backend.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.List;

@JsonIgnoreProperties(ignoreUnknown = true)
public record AiPlaneMeasurementV2Dto(
    String id,
    String labelKey,
    Double value,
    String unit,
    Double confidence,
    String status,
    String measurementBasis,
    List<String> linkedLandmarkIds
) {}
