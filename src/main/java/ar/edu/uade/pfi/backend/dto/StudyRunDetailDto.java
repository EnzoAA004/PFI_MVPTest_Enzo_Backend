package ar.edu.uade.pfi.backend.dto;

import ar.edu.uade.pfi.backend.domain.MeasurementCorrection;
import java.util.List;
import java.util.Map;

public record StudyRunDetailDto(
    StudyRunSummaryDto summary,
    Map<String, List<Map<String, Object>>> measurementsByPlane,
    Map<String, List<Map<String, Object>>> artifactsByPlane,
    List<MeasurementCorrection> corrections,
    Map<String, Object> metricsSnapshot
) {}
