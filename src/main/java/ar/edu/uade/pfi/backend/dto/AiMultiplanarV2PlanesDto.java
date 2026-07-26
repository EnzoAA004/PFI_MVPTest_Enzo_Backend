package ar.edu.uade.pfi.backend.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

@JsonIgnoreProperties(ignoreUnknown = false)
public record AiMultiplanarV2PlanesDto(
    AiPlaneRunV2Dto sagittal,
    AiPlaneRunV2Dto axial
) {}
