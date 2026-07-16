package ar.edu.uade.pfi.backend.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

@JsonIgnoreProperties(ignoreUnknown = true)
public record AiInputResponseDto(
    String inputId,
    String caseId,
    String plane,
    String format,
    long size
) {}
