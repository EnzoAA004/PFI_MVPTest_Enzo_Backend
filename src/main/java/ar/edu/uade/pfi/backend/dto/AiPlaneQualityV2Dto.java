package ar.edu.uade.pfi.backend.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

@JsonIgnoreProperties(ignoreUnknown = true)
public record AiPlaneQualityV2Dto(
    Double confidence,
    Integer maskCount,
    Integer landmarkCount,
    Integer measurementCount
) {}
