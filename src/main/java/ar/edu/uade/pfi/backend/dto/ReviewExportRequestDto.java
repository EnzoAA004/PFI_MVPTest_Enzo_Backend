package ar.edu.uade.pfi.backend.dto;

import java.util.List;

public record ReviewExportRequestDto(
    String format,
    String caseId,
    String subjectRef,
    String studyDate,
    String plane,
    String modelKey,
    String modelVersion,
    String inferenceMode,
    String modelReadiness,
    String notes,
    String reviewer,
    List<MeasurementSaveDto> measurements
) {}
