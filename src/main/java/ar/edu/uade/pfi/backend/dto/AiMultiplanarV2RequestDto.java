package ar.edu.uade.pfi.backend.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import jakarta.validation.constraints.NotBlank;

@JsonIgnoreProperties(ignoreUnknown = false)
public record AiMultiplanarV2RequestDto(
    @NotBlank String caseId,
    String traceId,
    String inferenceMode,
    Boolean allowContractFallback,
    AiMultiplanarV2PlanesRequestDto planes,
    AiMultiplanarV2OptionsDto options
) {}
