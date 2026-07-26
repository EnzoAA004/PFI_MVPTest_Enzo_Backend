package ar.edu.uade.pfi.backend.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import jakarta.validation.constraints.NotBlank;
import java.util.Map;

@JsonIgnoreProperties(ignoreUnknown = true)
public record MultiplanarRunApiRequestDto(
    @NotBlank String caseId,
    StudyMetadataDto studyMetadata,
    String sagittalInputId,
    String axialInputId,
    String sagittalInputPath,
    String axialInputPath,
    String sagittalModelKey,
    String axialModelKey,
    Boolean allowContractFallback,
    Map<String, Object> metadata
) {}
