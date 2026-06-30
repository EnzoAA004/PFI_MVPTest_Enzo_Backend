package ar.edu.uade.pfi.backend.dto;

import jakarta.validation.constraints.NotBlank;
import java.util.Map;

public record PipelineRunRequestDto(
    @NotBlank String caseId,
    @NotBlank String plane,
    String modelKey,
    String inputPath,
    Map<String, Object> metadata
) {}
