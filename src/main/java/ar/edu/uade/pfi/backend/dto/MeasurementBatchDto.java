package ar.edu.uade.pfi.backend.dto;

import java.util.List;

public record MeasurementBatchDto(
    List<MeasurementSaveDto> measurements,
    String reviewer,
    String detail
) {}
