package ar.edu.uade.pfi.backend.dto;

import java.util.List;

public record StudyUploadResponseDto(
    String caseId,
    String studyId,
    List<StudyUploadSeriesDto> seriesFound,
    StudyUploadInputDto sagittal,
    StudyUploadInputDto axial,
    /**
     * Sagittal T1 and T2 as two independently registered, independently analyzable inputs (P10.7
     * treats them as separate modalities, never pixel-registered to each other). {@code sagittal}
     * above stays the single-plane winner used by the legacy {@code /api/ai/multiplanar/run}
     * contract; these are for the P10.9 full-series and disc-degenerative-findings product routes,
     * which need both when both exist.
     */
    StudyUploadInputDto sagittalT1,
    StudyUploadInputDto sagittalT2,
    List<String> warnings,
    boolean humanReviewRequired,
    boolean notClinicalDiagnosis) {}
