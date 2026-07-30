package ar.edu.uade.pfi.backend.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.List;

@JsonIgnoreProperties(ignoreUnknown = true)
public record AiSliceEntryV2Dto(
    Integer index,
    Integer displayIndex,
    AiSliceAssetV2Dto previewAsset,
    Boolean hasResults,
    AiSliceAssetV2Dto overlayAsset,
    List<String> measurementIds,
    List<String> landmarkIds
) {}
