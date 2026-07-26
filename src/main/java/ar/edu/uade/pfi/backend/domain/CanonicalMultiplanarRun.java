package ar.edu.uade.pfi.backend.domain;

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
    Map<String, Object> review,
    Governance governance
) {
    public CanonicalMultiplanarRun {
        Objects.requireNonNull(status, "status");
        Objects.requireNonNull(multiplanarRunId, "multiplanarRunId");
        Objects.requireNonNull(governance, "governance");
        requestedPlanes = requestedPlanes == null ? List.of() : List.copyOf(requestedPlanes);
        completedPlanes = completedPlanes == null ? List.of() : List.copyOf(completedPlanes);
        readiness = readiness == null ? Map.of() : Map.copyOf(readiness);
        planes = planes == null ? Map.of() : Map.copyOf(planes);
        threeD = threeD == null ? Map.of() : Map.copyOf(threeD);
        quality = quality == null ? Map.of() : Map.copyOf(quality);
        review = review == null ? Map.of() : Map.copyOf(review);
    }

    public CanonicalPlaneRun sagittal() {
        return planes.get("sagittal");
    }

    public CanonicalPlaneRun axial() {
        return planes.get("axial");
    }

    public record Governance(boolean humanReviewRequired, boolean notClinicalDiagnosis, boolean deidentified, boolean diagnosisGenerated) {}
}
