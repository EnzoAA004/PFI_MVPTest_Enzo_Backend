package ar.edu.uade.pfi.backend.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

@JsonIgnoreProperties(ignoreUnknown = false)
public record AiMultiplanarV2OptionsDto(
    Integer sliceIndex,
    Integer sliceAxis,
    Integer sliceWindowRadius,
    String inputOrientationTransform
) {}
