package ar.edu.uade.pfi.backend.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import java.util.List;
import java.util.Map;

@JsonIgnoreProperties(ignoreUnknown = true)
public record MultiplanarRunRequestDto(
    @NotBlank String caseId,
    String sagittalInputId,
    String axialInputId,
    String sagittalInputPath,
    String axialInputPath,
    String sagittalModelKey,
    String axialModelKey,
    Boolean allowContractFallback,
    /**
     * Every series the uploaded study carried, so the reader can display them.
     *
     * <p>Optional, and empty for a run built from two per-plane uploads rather than a
     * study archive. The two analysed planes still travel in
     * {@code sagittalInputId}/{@code axialInputId}: this list does not replace them,
     * it adds the ones no model runs on — the T1s, the axial T1 with no model, the
     * localizer — which the doctor needs and which used to be discarded at ingestion.
     */
    @Valid List<StudyUploadSeriesDto> studySeries,
    Map<String, Object> metadata
) {
    /**
     * A run with no study catalogue behind it: two per-plane uploads.
     *
     * <p>Kept as its own constructor rather than making every caller pass {@code null}:
     * "this run did not come from a study archive" is a real case, not an omission.
     */
    public MultiplanarRunRequestDto(
        String caseId,
        String sagittalInputId,
        String axialInputId,
        String sagittalInputPath,
        String axialInputPath,
        String sagittalModelKey,
        String axialModelKey,
        Boolean allowContractFallback,
        Map<String, Object> metadata
    ) {
        this(caseId, sagittalInputId, axialInputId, sagittalInputPath, axialInputPath,
            sagittalModelKey, axialModelKey, allowContractFallback, List.of(), metadata);
    }

    public MultiplanarRunRequestDto {
        if (studySeries == null) studySeries = List.of();
    }
}
