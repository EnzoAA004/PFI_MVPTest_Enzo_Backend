package ar.edu.uade.pfi.backend.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonAlias;
import java.util.List;
import java.util.Map;

@JsonIgnoreProperties(ignoreUnknown = true)
public record MultiplanarRunResponseDto(
    @JsonAlias("multiplanarRunId")
    String runId,
    String traceId,
    String effectiveInferenceMode,
    PlanesDto planes,
    Map<String, Object> assets,
    Map<String, Object> review
) {
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record PlanesDto(
        @JsonAlias("sagittal")
        PlaneDto sagital,
        PlaneDto axial
    ) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record PlaneDto(
        String runId,
        String plane,
        String modelKey,
        String status,
        String effectiveInferenceMode,
        Map<String, Object> modelArtifact,
        List<Map<String, Object>> findings,
        List<Map<String, Object>> landmarks,
        Map<String, Object> measurements,
        Map<String, Object> evidence,
        Map<String, Object> assets
    ) {}
}
