package ar.edu.uade.pfi.backend.dto;

import jakarta.validation.constraints.NotBlank;
import java.util.List;

public record DiscDegenerativeMultitaskRequestDto(
    @NotBlank String multiplanarRunId,
    List<String> levels
) {}
