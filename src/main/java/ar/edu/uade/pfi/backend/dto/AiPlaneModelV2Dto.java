package ar.edu.uade.pfi.backend.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

@JsonIgnoreProperties(ignoreUnknown = true)
public record AiPlaneModelV2Dto(
    String modelKey,
    String modelVersion,
    String artifactHash,
    Boolean baselineReady,
    Boolean availableForRealInference,
    Boolean manifestValid
) {}
