package ar.edu.uade.pfi.backend.dto;

import java.time.Instant;

public record ReviewExportResponseDto(
    String status,
    String format,
    String fileName,
    String mimeType,
    String content,
    String runId,
    String caseId,
    Boolean deidentified,
    Boolean rawImagesIncluded,
    Boolean humanReviewRequired,
    Boolean notClinicalDiagnosis,
    Instant generatedAt
) {}
