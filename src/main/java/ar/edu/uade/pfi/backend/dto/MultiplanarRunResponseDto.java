package ar.edu.uade.pfi.backend.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.List;
import java.util.Map;

@JsonIgnoreProperties(ignoreUnknown = true)
public record MultiplanarRunResponseDto(
    String runId,
    String traceId,
    String effectiveInferenceMode,
    PlanesDto planes,
    Map<String, Object> assets,
    Map<String, Object> review
) {
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record PlanesDto(
        PlaneDto sagital,
        PlaneDto axial
    ) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record PlaneDto(
        String plane,
        String modelKey,
        String status,
        List<Map<String, Object>> findings,
        Map<String, Object> measurements,
        Map<String, Object> evidence
    ) {}
}
