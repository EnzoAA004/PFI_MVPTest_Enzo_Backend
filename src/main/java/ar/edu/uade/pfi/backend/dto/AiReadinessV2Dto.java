package ar.edu.uade.pfi.backend.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

@JsonIgnoreProperties(ignoreUnknown = false)
public record AiReadinessV2Dto(
    Boolean sagittal,
    Boolean axial,
    Boolean dual
) {}
