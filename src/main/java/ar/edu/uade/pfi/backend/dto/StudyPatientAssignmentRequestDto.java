package ar.edu.uade.pfi.backend.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

@JsonIgnoreProperties(ignoreUnknown = false)
public record StudyPatientAssignmentRequestDto(
    String patientId, String expectedPatientId, String reason) {}
