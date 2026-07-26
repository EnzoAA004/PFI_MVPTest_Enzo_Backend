package ar.edu.uade.pfi.backend.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.List;

@JsonIgnoreProperties(ignoreUnknown = false)
public record AiPlaneRunV2Dto(
    String status,
    String plane,
    String runId,
    String effectiveInferenceMode,
    AiPlaneModelV2Dto model,
    AiPlaneInputV2Dto input,
    AiCoordinateSpaceV2Dto coordinateSpace,
    List<AiPlaneSeriesV2Dto> series,
    List<AiPlaneAssetV2Dto> assets,
    List<AiPlaneMaskV2Dto> masks,
    List<AiPlaneLandmarkV2Dto> landmarks,
    List<AiPlaneMeasurementV2Dto> measurements,
    AiPlaneQualityV2Dto quality,
    Boolean synthetic,
    String fallbackReason
) {}
