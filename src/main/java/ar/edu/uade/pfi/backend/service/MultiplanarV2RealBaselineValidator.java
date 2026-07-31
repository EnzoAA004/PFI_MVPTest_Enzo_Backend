package ar.edu.uade.pfi.backend.service;

import ar.edu.uade.pfi.backend.domain.CanonicalMultiplanarRun;
import ar.edu.uade.pfi.backend.domain.CanonicalPlaneRun;
import ar.edu.uade.pfi.backend.dto.MultiplanarRunRequestDto;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Service;

/**
 * Strict production gate for the pfi.multiplanar-run.v2 contract. Only runs for
 * "strict" requests (inferenceMode=real_baseline, allowContractFallback=false); any
 * violation throws AiMultiplanarContractViolationException (mapped to 502
 * AI_MULTIPLANAR_CONTRACT_VIOLATION) before the canonical result is ever persisted.
 */
@Service
public class MultiplanarV2RealBaselineValidator {
    private static final String V2_SCHEMA_VERSION = "pfi.multiplanar-run.v2";
    static final String SAGITTAL_MODEL_KEY = "sagittal_spider";
    static final String SAGITTAL_MODEL_VERSION = "sagittal-spider-final-v1";
    static final String SAGITTAL_ARTIFACT_HASH = "cf11dcc0ad77a7c787e64a796a2fd7398ef906add461cef4b3d61f1a5238e944";

    // Frozen-artifact policy for sagittal_spider/sagittal-spider-final-v1 ONLY.
    // Do not generalize these exact counts to future sagittal models/versions.
    private static final int FROZEN_MASK_COUNT = 3;
    private static final int FROZEN_LANDMARK_COUNT = 3;
    private static final int FROZEN_MEASUREMENT_COUNT = 9;
    private static final int MAGNITUDES_PER_STRUCTURE = 3;

    public boolean isStrict(MultiplanarRunRequestDto request) {
        if (request == null || request.metadata() == null) {
            return request != null && Boolean.FALSE.equals(request.allowContractFallback());
        }
        String inferenceMode = text(request.metadata().get("inferenceMode"));
        Object metadataFallback = request.metadata().get("allowContractFallback");
        boolean fallbackFalse = Boolean.FALSE.equals(request.allowContractFallback()) || Boolean.FALSE.equals(metadataFallback);
        return "real_baseline".equals(inferenceMode) && fallbackFalse;
    }

    public void validate(MultiplanarRunRequestDto request, CanonicalMultiplanarRun response) {
        if (!isStrict(request)) return;
        require(response != null, "respuesta multiplanar v2 vacia");

        requireEquals("completed", response.status(), "status root");
        requireEquals(V2_SCHEMA_VERSION, response.schemaVersion(), "schemaVersion root");
        requireNotBlank(response.multiplanarRunId(), "multiplanarRunId root");
        requireTrue(response.multiplanarRunId().startsWith("multi-"), "multiplanarRunId root debe comenzar con 'multi-'");
        requireEquals(request.caseId(), response.caseId(), "caseId root");
        requireNotBlank(response.traceId(), "traceId root");
        requireEquals("real_baseline", response.requestedInferenceMode(), "requestedInferenceMode root");
        requireEquals("real_baseline", response.effectiveInferenceMode(), "effectiveInferenceMode root");
        requireTrue(!response.synthetic(), "synthetic root debe ser false");
        requireTrue(response.fallbackReason() == null, "fallbackReason root debe ser null");

        List<String> requestedPlanes = requestedPlanes(request);
        requireSamePlaneSet(requestedPlanes, response.requestedPlanes(), "requestedPlanes");
        requireSamePlaneSet(requestedPlanes, response.completedPlanes(), "completedPlanes");
        requireWorkspaceMode(requestedPlanes, response.workspaceMode());

        requireTrue(Boolean.TRUE.equals(response.review().get("required")), "review.required debe ser true");
        requireEquals("pending", text(response.review().get("status")), "review.status");

        CanonicalMultiplanarRun.Governance governance = response.governance();
        requireTrue(governance.humanReviewRequired(), "governance.humanReviewRequired debe ser true");
        requireTrue(governance.notClinicalDiagnosis(), "governance.notClinicalDiagnosis debe ser true");
        requireTrue(governance.deidentified(), "governance.deidentified debe ser true");
        requireTrue(!governance.diagnosisGenerated(), "governance.diagnosisGenerated debe ser false");

        validateSagittal(request, response.sagittal(), response.multiplanarRunId());

        if (requestedPlanes.contains("axial")) {
            require(response.axial() != null, "planes.axial obligatorio cuando fue solicitado");
        } else {
            requireTrue(response.axial() == null, "planes.axial debe ser null cuando no fue solicitado");
        }
    }

    private void validateSagittal(MultiplanarRunRequestDto request, CanonicalPlaneRun plane, String multiplanarRunId) {
        require(plane != null, "plano sagittal obligatorio");
        requireEquals("sagittal", plane.plane(), "plane sagittal");
        requireEquals("ready", plane.status(), "status sagittal");
        requireNotBlank(plane.planeRunId(), "planeRunId sagittal");
        requireTrue(!plane.planeRunId().equals(multiplanarRunId), "planeRunId sagittal no puede ser igual a multiplanarRunId");
        requireEquals("real_baseline", plane.effectiveInferenceMode(), "effectiveInferenceMode sagittal");
        requireTrue(!plane.synthetic(), "synthetic sagittal debe ser false");
        requireTrue(plane.fallbackReason() == null, "fallbackReason sagittal debe ser null");

        Map<String, Object> model = plane.model();
        requireEquals(SAGITTAL_MODEL_KEY, text(model.get("key")), "model.key sagittal");
        requireEquals(SAGITTAL_MODEL_VERSION, text(model.get("version")), "model.version sagittal");
        requireEquals(SAGITTAL_ARTIFACT_HASH, text(model.get("artifactHash")), "model.artifactHash sagittal");
        requireTrue(Boolean.TRUE.equals(model.get("baselineReady")), "model.baselineReady sagittal debe ser true");
        requireTrue(Boolean.TRUE.equals(model.get("availableForRealInference")), "model.availableForRealInference sagittal debe ser true");
        requireTrue(Boolean.TRUE.equals(model.get("manifestValid")), "model.manifestValid sagittal debe ser true");

        Map<String, Object> input = plane.input();
        if (!blank(request.sagittalInputId())) {
            requireEquals(request.sagittalInputId(), text(input.get("inputId")), "input.inputId sagittal");
        }
        List<Integer> nativeShape = intList(input.get("nativeShape"));
        List<Integer> canonicalShape = intList(input.get("canonicalShape"));
        requireTrue(isValidShape(nativeShape), "input.nativeShape sagittal invalido");
        requireTrue(isValidShape(canonicalShape), "input.canonicalShape sagittal invalido");
        int sliceCount = nativeShape.get(nativeShape.size() - 1);
        requireTrue(sliceCount > 0, "sliceCount sagittal debe ser mayor a 0");
        Object selectedSliceIndex = input.get("selectedSliceIndex");
        if (selectedSliceIndex != null) {
            int selected = intValue(selectedSliceIndex, -1);
            requireTrue(selected >= 0 && selected < sliceCount, "selectedSliceIndex sagittal fuera de rango");
        }

        Map<String, Object> coordinateSpace = plane.coordinateSpace();
        requireTrue(intValue(coordinateSpace.get("width"), 0) > 0, "coordinateSpace.width sagittal debe ser mayor a 0");
        requireTrue(intValue(coordinateSpace.get("height"), 0) > 0, "coordinateSpace.height sagittal debe ser mayor a 0");

        requireTrue(!plane.series().isEmpty(), "series sagittal no puede estar vacia");
        requireTrue(containsAsset(plane.assets(), "input.png"), "assets sagittal debe contener input.png");
        requireTrue(containsAsset(plane.assets(), "overlay.png"), "assets sagittal debe contener overlay.png");
        for (Map<String, Object> asset : plane.assets()) {
            requireValidAssetPath(text(asset.get("relativePath")), text(asset.get("assetName")));
        }

        for (Map<String, Object> mask : plane.masks()) {
            requireNotBlank(text(mask.get("id")), "mask.id sagittal");
            requireNotBlank(text(mask.get("classKey")), "mask.classKey sagittal");
        }
        for (Map<String, Object> landmark : plane.landmarks()) {
            requireNotBlank(text(landmark.get("id")), "landmark.id sagittal");
            requireNotBlank(text(landmark.get("labelKey")), "landmark.labelKey sagittal");
        }
        for (Map<String, Object> measurement : plane.measurements()) {
            requireNotBlank(text(measurement.get("id")), "measurement.id sagittal");
            requireNotBlank(text(measurement.get("labelKey")), "measurement.labelKey sagittal");
            requireTrue(!measurement.containsKey("reviewerValue"), "measurement.reviewerValue no debe existir en la salida de IA");
        }

        validateFrozenSagittalSpiderArtifactCounts(model, plane);
        validateQualityConsistency(plane);
    }

    /**
     * Frozen-artifact regression check: the sagittal_spider/sagittal-spider-final-v1
     * release is pinned to exactly 3 masks and 3 landmarks. This is a point-in-time
     * assertion about THIS specific model release, not a universal rule — it must not
     * fire for any other modelKey/modelVersion combination.
     *
     * <p>The measurement count used to be pinned at 9 (area/width/height for each of
     * the 3 classes). It is now a floor: the AI Module splits the {@code disc_group}
     * class into its connected components so each disc space carries its own
     * measurements and its own lumbar level, and the number of discs depends on the
     * study's field of view. The invariant that still holds — and that would catch the
     * drift the fixed count was there to catch — is that every structure contributes
     * exactly its three magnitudes on top of the two unchanged group classes.
     */
    private void validateFrozenSagittalSpiderArtifactCounts(Map<String, Object> model, CanonicalPlaneRun plane) {
        boolean isFrozenRelease = SAGITTAL_MODEL_KEY.equals(text(model.get("key")))
            && SAGITTAL_MODEL_VERSION.equals(text(model.get("version")));
        if (!isFrozenRelease) return;
        requireTrue(plane.masks().size() == FROZEN_MASK_COUNT, "sagittal_spider/sagittal-spider-final-v1 debe tener exactamente 3 masks");
        requireTrue(plane.landmarks().size() == FROZEN_LANDMARK_COUNT, "sagittal_spider/sagittal-spider-final-v1 debe tener exactamente 3 landmarks");
        int measurementCount = plane.measurements().size();
        requireTrue(measurementCount >= FROZEN_MEASUREMENT_COUNT, "sagittal_spider/sagittal-spider-final-v1 debe tener al menos 9 measurements");
        requireTrue(measurementCount % MAGNITUDES_PER_STRUCTURE == 0, "sagittal_spider/sagittal-spider-final-v1 debe tener area/width/height por estructura");
    }

    private void validateQualityConsistency(CanonicalPlaneRun plane) {
        Map<String, Object> quality = plane.quality();
        Object maskCount = quality.get("maskCount");
        if (maskCount != null) {
            requireTrue(intValue(maskCount, -1) == plane.masks().size(), "quality.maskCount inconsistente con masks");
        }
        Object landmarkCount = quality.get("landmarkCount");
        if (landmarkCount != null) {
            requireTrue(intValue(landmarkCount, -1) == plane.landmarks().size(), "quality.landmarkCount inconsistente con landmarks");
        }
        Object measurementCount = quality.get("measurementCount");
        if (measurementCount != null) {
            requireTrue(intValue(measurementCount, -1) == plane.measurements().size(), "quality.measurementCount inconsistente con measurements");
        }
    }

    private void requireValidAssetPath(String relativePath, String assetName) {
        if (relativePath.isBlank()) return;
        String normalized = relativePath.toLowerCase(java.util.Locale.ROOT);
        requireTrue(!normalized.contains(".."), "asset " + assetName + " tiene un relativePath con path traversal");
        requireTrue(!normalized.matches("^[a-z][a-z0-9+.-]*://.*"), "asset " + assetName + " tiene un relativePath con URL externa");
        requireTrue(!normalized.contains("localhost") && !normalized.contains("cloudflare") && !normalized.contains("host.docker.internal"),
            "asset " + assetName + " tiene un relativePath con host no permitido");
    }

    private boolean containsAsset(List<Map<String, Object>> assets, String assetName) {
        for (Map<String, Object> asset : assets) {
            if (assetName.equals(text(asset.get("assetName")))) return true;
        }
        return false;
    }

    private boolean isValidShape(List<Integer> shape) {
        if (shape == null || shape.size() != 3) return false;
        for (Integer value : shape) {
            if (value == null || value <= 0) return false;
        }
        return true;
    }

    @SuppressWarnings("unchecked")
    private List<Integer> intList(Object value) {
        if (!(value instanceof List<?> list)) return List.of();
        List<Integer> result = new ArrayList<>();
        for (Object item : list) {
            result.add(intValue(item, Integer.MIN_VALUE));
        }
        return result;
    }

    private List<String> requestedPlanes(MultiplanarRunRequestDto request) {
        List<String> planes = new ArrayList<>();
        if (!blank(request.sagittalInputId()) || !blank(request.sagittalInputPath())) planes.add("sagittal");
        if (!blank(request.axialInputId()) || !blank(request.axialInputPath())) planes.add("axial");
        return planes;
    }

    private void requireSamePlaneSet(List<String> expected, List<String> actual, String field) {
        Set<String> expectedSet = new LinkedHashSet<>(expected == null ? List.of() : expected);
        Set<String> actualSet = new LinkedHashSet<>(actual == null ? List.of() : actual);
        requireTrue(expectedSet.equals(actualSet), field + " no coincide con los planos solicitados: esperado=" + expectedSet + " actual=" + actualSet);
    }

    private void requireWorkspaceMode(List<String> requestedPlanes, String workspaceMode) {
        String mode = text(workspaceMode);
        requireTrue(!mode.isBlank(), "workspaceMode root no puede estar vacio");
        if (requestedPlanes.size() == 1) {
            requireEquals(requestedPlanes.get(0) + "_only", mode, "workspaceMode root");
        } else if (requestedPlanes.size() == 2) {
            requireTrue(!mode.equals("sagittal_only") && !mode.equals("axial_only"), "workspaceMode root debe reflejar ambos planos solicitados");
        }
    }

    private int intValue(Object value, int fallback) {
        if (value instanceof Number number) return number.intValue();
        try {
            return Integer.parseInt(String.valueOf(value));
        } catch (RuntimeException ex) {
            return fallback;
        }
    }

    private String text(Object value) {
        return value == null ? "" : String.valueOf(value).trim();
    }

    private void requireEquals(String expected, String actual, String field) {
        if (!text(expected).equals(text(actual))) fail(field + " esperado=" + expected + " actual=" + actual);
    }

    private void requireNotBlank(String value, String field) {
        require(!blank(value), field + " no puede estar vacio");
    }

    private void requireTrue(boolean condition, String message) {
        require(condition, message);
    }

    private void require(boolean condition, String message) {
        if (!condition) fail(message);
    }

    private void fail(String message) {
        throw new AiMultiplanarContractViolationException(message);
    }

    private boolean blank(String value) {
        return value == null || value.isBlank();
    }
}
