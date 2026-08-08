package ar.edu.uade.pfi.backend.domain;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

public record CanonicalMultiplanarRun(
    String status,
    String schemaVersion,
    String multiplanarRunId,
    String traceId,
    String caseId,
    String workspaceMode,
    String requestedInferenceMode,
    String effectiveInferenceMode,
    List<String> requestedPlanes,
    List<String> completedPlanes,
    boolean synthetic,
    String fallbackReason,
    Map<String, Object> readiness,
    Map<String, CanonicalPlaneRun> planes,
    Map<String, Object> threeD,
    Map<String, Object> quality,
    Map<String, Object> degenerativeFindings,
    Map<String, Object> review,
    Governance governance) {
  public CanonicalMultiplanarRun {
    Objects.requireNonNull(status, "status");
    Objects.requireNonNull(multiplanarRunId, "multiplanarRunId");
    Objects.requireNonNull(governance, "governance");
    requestedPlanes = requestedPlanes == null ? List.of() : List.copyOf(requestedPlanes);
    completedPlanes = completedPlanes == null ? List.of() : List.copyOf(completedPlanes);
    readiness = immutableMap(readiness);
    planes = immutableMap(planes);
    threeD = immutableMap(threeD);
    quality = immutableMap(quality);
    degenerativeFindings = immutableMap(degenerativeFindings);
    review = immutableMap(review);
  }

  public CanonicalMultiplanarRun(
      String status,
      String schemaVersion,
      String multiplanarRunId,
      String traceId,
      String caseId,
      String workspaceMode,
      String requestedInferenceMode,
      String effectiveInferenceMode,
      List<String> requestedPlanes,
      List<String> completedPlanes,
      boolean synthetic,
      String fallbackReason,
      Map<String, Object> readiness,
      Map<String, CanonicalPlaneRun> planes,
      Map<String, Object> threeD,
      Map<String, Object> quality,
      Map<String, Object> review,
      Governance governance) {
    this(
        status,
        schemaVersion,
        multiplanarRunId,
        traceId,
        caseId,
        workspaceMode,
        requestedInferenceMode,
        effectiveInferenceMode,
        requestedPlanes,
        completedPlanes,
        synthetic,
        fallbackReason,
        readiness,
        planes,
        threeD,
        quality,
        Map.of(),
        review,
        governance);
  }

  public CanonicalPlaneRun sagittal() {
    return planes.get("sagittal");
  }

  public CanonicalPlaneRun axial() {
    return planes.get("axial");
  }

  public record Governance(
      boolean humanReviewRequired,
      boolean notClinicalDiagnosis,
      boolean deidentified,
      boolean diagnosisGenerated) {}

  private static <K, V> Map<K, V> immutableMap(Map<K, V> value) {
    if (value == null || value.isEmpty()) return Map.of();
    return Collections.unmodifiableMap(new LinkedHashMap<>(value));
  }
}
