package ar.edu.uade.pfi.backend.dto;

import java.util.Map;

public record AiRunResponseDto(
    String runId,
    boolean humanReviewRequired,
    Map<String, Object> payload,
    ReviewStatusDto review
) {}
