package ar.edu.uade.pfi.backend.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

@JsonIgnoreProperties(ignoreUnknown = false)
public record AiReviewPolicyV2Dto(
    String status,
    Boolean required,
    Boolean approvalRequiresHumanConfirmation
) {}
