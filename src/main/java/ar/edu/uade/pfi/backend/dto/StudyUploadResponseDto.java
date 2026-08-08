package ar.edu.uade.pfi.backend.dto;

import java.util.List;

public record StudyUploadResponseDto(
    String caseId,
    String studyId,
    List<StudyUploadSeriesDto> seriesFound,
    StudyUploadInputDto sagittal,
    StudyUploadInputDto axial,
    List<String> warnings,
    boolean humanReviewRequired,
    boolean notClinicalDiagnosis) {}
