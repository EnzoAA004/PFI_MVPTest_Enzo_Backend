package ar.edu.uade.pfi.backend.dto;

import java.util.List;

public record StudyRunsResponseDto(
    String status,
    String caseId,
    List<StudyRunDetailDto> runs,
    boolean humanReviewRequired,
    boolean notClinicalDiagnosis
) {}
