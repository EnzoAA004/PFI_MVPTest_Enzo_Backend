package ar.edu.uade.pfi.backend.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

@JsonIgnoreProperties(ignoreUnknown = false)
public record AiGovernanceV2Dto(
    Boolean humanReviewRequired,
    Boolean notClinicalDiagnosis,
    Boolean deidentified,
    Boolean diagnosisGenerated) {}
