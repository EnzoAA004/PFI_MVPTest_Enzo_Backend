package ar.edu.uade.pfi.backend.dto;

import jakarta.validation.constraints.NotBlank;
import java.util.Map;

public record MultiplanarRunRequestDto(
    @NotBlank String caseId,
    String sagittalInputPath,
    String axialInputPath,
    String sagittalModelKey,
    String axialModelKey,
    Map<String, Object> metadata
) {}
