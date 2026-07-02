package ar.edu.uade.pfi.backend.dto;

public record StudyRowDto(
    String caseId,
    String subjectRef,
    String plane,
    String studyDate,
    String modelKey,
    String modelStatus,
    String reviewStatus,
    String priority,
    String runId
) {}
