package ar.edu.uade.pfi.backend.dto;

import java.util.List;
import java.util.Map;

public record StudyListResponseDto(
    String status,
    String source,
    String dataOrigin,
    List<StudyRowDto> items,
    Map<String, Object> summary,
    boolean humanReviewRequired,
    boolean notClinicalDiagnosis
) {}
