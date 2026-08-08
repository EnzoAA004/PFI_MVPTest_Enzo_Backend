package ar.edu.uade.pfi.backend.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

@JsonIgnoreProperties(ignoreUnknown = false)
public record AiPlaneModelV2Dto(
    String key,
    String version,
    String readiness,
    String trainingStatus,
    String artifactHash,
    Boolean baselineReady,
    Boolean availableForRealInference,
    String manifestStatus,
    Boolean manifestValid) {}
