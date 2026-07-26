package ar.edu.uade.pfi.backend.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

@JsonIgnoreProperties(ignoreUnknown = false)
public record AiPlaneExecutionV2RequestDto(
    String inputId,
    String modelKey
) {}
