package ar.edu.uade.pfi.backend.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

@JsonIgnoreProperties(ignoreUnknown = true)
public record AiPlaneLandmarkV2Dto(
    String id,
    String labelKey,
    Double x,
    Double y,
    Double z,
    Double confidence
) {}
