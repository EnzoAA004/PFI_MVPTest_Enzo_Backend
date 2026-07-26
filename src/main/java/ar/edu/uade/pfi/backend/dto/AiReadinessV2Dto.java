package ar.edu.uade.pfi.backend.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

@JsonIgnoreProperties(ignoreUnknown = true)
public record AiReadinessV2Dto(
    Boolean sagittalReady,
    Boolean axialReady,
    Boolean dualRunReady
) {}
