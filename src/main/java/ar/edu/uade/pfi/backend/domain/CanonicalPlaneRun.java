package ar.edu.uade.pfi.backend.domain;

import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Collections;

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
        model = immutableMap(model);
        input = immutableMap(input);
        coordinateSpace = immutableMap(coordinateSpace);
        series = series == null ? List.of() : List.copyOf(series);
        assets = assets == null ? List.of() : List.copyOf(assets);
        masks = masks == null ? List.of() : List.copyOf(masks);
        landmarks = landmarks == null ? List.of() : List.copyOf(landmarks);
        measurements = measurements == null ? List.of() : List.copyOf(measurements);
        quality = immutableMap(quality);
    }

    private static <K, V> Map<K, V> immutableMap(Map<K, V> value) {
        if (value == null || value.isEmpty()) return Map.of();
        return Collections.unmodifiableMap(new LinkedHashMap<>(value));
    }
}
