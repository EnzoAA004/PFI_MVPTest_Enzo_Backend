package ar.edu.uade.pfi.backend.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

@JsonIgnoreProperties(ignoreUnknown = true)
public record AiPlaneMaskV2Dto(
    String id,
    String classKey,
    String assetName
) {}
