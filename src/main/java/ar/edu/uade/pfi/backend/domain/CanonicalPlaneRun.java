package ar.edu.uade.pfi.backend.domain;

import java.util.List;
import java.util.Map;
import java.util.Objects;

public record CanonicalPlaneRun(
    String planeRunId,
    String plane,
    String status,
    String effectiveInferenceMode,
    boolean synthetic,
    String fallbackReason,
    Map<String, Object> model,
    Map<String, Object> input,
    Map<String, Object> coordinateSpace,
    List<Map<String, Object>> series,
    List<Map<String, Object>> assets,
    List<Map<String, Object>> masks,
    List<Map<String, Object>> landmarks,
    List<Map<String, Object>> measurements,
    Map<String, Object> quality
) {
    public CanonicalPlaneRun {
        Objects.requireNonNull(plane, "plane");
        model = model == null ? Map.of() : Map.copyOf(model);
        input = input == null ? Map.of() : Map.copyOf(input);
        coordinateSpace = coordinateSpace == null ? Map.of() : Map.copyOf(coordinateSpace);
        series = series == null ? List.of() : List.copyOf(series);
        assets = assets == null ? List.of() : List.copyOf(assets);
        masks = masks == null ? List.of() : List.copyOf(masks);
        landmarks = landmarks == null ? List.of() : List.copyOf(landmarks);
        measurements = measurements == null ? List.of() : List.copyOf(measurements);
        quality = quality == null ? Map.of() : Map.copyOf(quality);
    }
}
