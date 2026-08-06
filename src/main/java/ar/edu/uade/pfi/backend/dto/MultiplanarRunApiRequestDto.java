package ar.edu.uade.pfi.backend.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import java.util.List;
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
    /**
     * Every series the uploaded study carried, as returned by the study upload.
     *
     * <p>Optional: a run built from two per-plane uploads has no study behind it and
     * sends nothing here. When present it is what lets the reading room show the
     * series no model ran on.
     */
    @Valid List<StudyUploadSeriesDto> studySeries,
    Map<String, Object> metadata
) {}
