package ar.edu.uade.pfi.backend.dto;

import java.time.Instant;
import java.util.List;

public record RunReviewResponseDto(
    String multiplanarRunId,
    String traceId,
    String reviewStatus,
    String reviewer,
    Instant reviewedAt,
    String comments,
    List<MeasurementCorrectionDto> corrections
) {}
