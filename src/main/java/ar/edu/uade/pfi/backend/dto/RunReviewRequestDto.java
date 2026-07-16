package ar.edu.uade.pfi.backend.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import jakarta.validation.constraints.NotBlank;
import java.util.List;

@JsonIgnoreProperties(ignoreUnknown = true)
public record RunReviewRequestDto(
    @NotBlank String reviewStatus,
    @NotBlank String reviewer,
    String comments,
    List<MeasurementCorrectionDto> corrections
) {}
