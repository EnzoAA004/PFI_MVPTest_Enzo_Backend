package ar.edu.uade.pfi.backend.dto;

import jakarta.validation.constraints.NotBlank;

public record ReviewUpdateRequestDto(
    @NotBlank String status,
    String notes,
    String reviewer
) {}
