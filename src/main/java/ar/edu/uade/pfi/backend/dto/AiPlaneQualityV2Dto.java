package ar.edu.uade.pfi.backend.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.List;

@JsonIgnoreProperties(ignoreUnknown = false)
public record AiPlaneQualityV2Dto(
    Integer maskCount,
    Integer landmarkCount,
    Integer measurementCount,
    Double meanConfidence,
    Double meanForegroundConfidence,
    Double foregroundRatio,
    /**
     * How many slices of the series have a persisted preview. May be lower than the
     * series' slice count: a run made before the preview catalog existed only kept
     * the inferred slice's image, and the viewer needs the difference to avoid
     * promising a frame that was never written.
     */
    Integer slicePreviewCount,
    List<String> warnings
) {}
