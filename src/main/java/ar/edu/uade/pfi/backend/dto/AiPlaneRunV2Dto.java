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
    /**
     * Instance label map for the inferred slice (RLE). Carried through as opaque
     * JSON: the backend neither renders nor interprets it — colour is a viewer
     * decision, which is the whole point of shipping the map instead of a PNG.
     */
    java.util.Map<String, Object> segmentation,
    AiPlaneQualityV2Dto quality,
    Boolean synthetic,
    String fallbackReason
) {}
