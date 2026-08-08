package ar.edu.uade.pfi.backend.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

@JsonIgnoreProperties(ignoreUnknown = false)
public record AiPlaneSeriesV2Dto(
    String id,
    String plane,
    String sequence,
    Integer selectedSliceIndex,
    Integer sliceCount,
    String status) {}
