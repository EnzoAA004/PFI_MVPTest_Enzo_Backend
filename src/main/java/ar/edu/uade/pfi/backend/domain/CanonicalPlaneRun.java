package ar.edu.uade.pfi.backend.domain;

import java.util.Collections;
import java.util.LinkedHashMap;
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
    /**
     * Instance label map for the inferred slice (RLE), carried through opaquely. The viewer paints
     * it: colour, visibility and opacity are presentation decisions, and shipping a rendered PNG
     * would freeze all three in the backend.
     */
    Map<String, Object> segmentation,
    Map<String, Object> quality) {
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
    segmentation = immutableMap(segmentation);
    quality = immutableMap(quality);
  }

  /**
   * A plane without an instance label map.
   *
   * <p>Valid on purpose: the v1 contract never carried one, and a synthetic run has nothing to map.
   * Those planes fall back to the composite overlay, which is what they actually produced.
   */
  public CanonicalPlaneRun(
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
      Map<String, Object> quality) {
    this(
        planeRunId,
        plane,
        status,
        effectiveInferenceMode,
        synthetic,
        fallbackReason,
        model,
        input,
        coordinateSpace,
        series,
        assets,
        masks,
        landmarks,
        measurements,
        null,
        quality);
  }

  private static <K, V> Map<K, V> immutableMap(Map<K, V> value) {
    if (value == null || value.isEmpty()) return Map.of();
    return Collections.unmodifiableMap(new LinkedHashMap<>(value));
  }
}
