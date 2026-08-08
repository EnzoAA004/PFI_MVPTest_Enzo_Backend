package ar.edu.uade.pfi.backend.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

@JsonIgnoreProperties(ignoreUnknown = false)
public record AiMultiplanarV2PlanesRequestDto(
    AiPlaneExecutionV2RequestDto sagittal, AiPlaneExecutionV2RequestDto axial) {}
