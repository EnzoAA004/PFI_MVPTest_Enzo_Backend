package ar.edu.uade.pfi.backend.dto;

import java.util.Map;

public record AgentDecisionDto(
    String label,
    Double confidence,
    Boolean humanReviewRequired,
    Map<String, Object> details
) {}
