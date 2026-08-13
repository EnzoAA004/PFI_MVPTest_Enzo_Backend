package ar.edu.uade.pfi.backend.dto;

import java.time.LocalDate;

public record PatientStudySummaryDto(
    String id,
    String caseId,
    LocalDate studyDate,
    String modality,
    String description,
    String reviewPriority,
    String status) {}
