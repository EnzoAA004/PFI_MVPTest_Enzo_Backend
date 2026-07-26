package ar.edu.uade.pfi.backend.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

@JsonIgnoreProperties(ignoreUnknown = false)
public record AiCoordinateSpaceV2Dto(
    String name,
    Integer width,
    Integer height,
    String units,
    String origin,
    String xDirection,
    String yDirection,
    Integer sourceSliceIndex,
    Integer sourceAxis
) {}
