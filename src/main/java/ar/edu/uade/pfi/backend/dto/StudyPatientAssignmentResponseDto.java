package ar.edu.uade.pfi.backend.dto;

public record StudyPatientAssignmentResponseDto(
    String studyId,
    String caseId,
    String patientId,
    String previousPatientId,
    String reasonCode,
    boolean changed) {}
