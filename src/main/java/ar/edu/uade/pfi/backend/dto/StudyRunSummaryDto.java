package ar.edu.uade.pfi.backend.dto;

import java.util.List;
import java.util.Map;

public record StudyRunSummaryDto(
    String runId,
    String databaseId,
    String traceId,
    String caseId,
    String requestedInferenceMode,
    String effectiveInferenceMode,
    String status,
    String reviewStatus,
    String reviewer,
    String reviewedAt,
    String comments,
    String sagittalRunId,
    String axialRunId,
    String sagittalModelKey,
    String axialModelKey,
    String sagittalArtifactHash,
    String axialArtifactHash,
    List<String> planes,
    int measurementCount,
    int artifactCount,
    String createdAt,
    String updatedAt,
    boolean humanReviewRequired,
    boolean notClinicalDiagnosis,
    String dataOrigin
) {}
