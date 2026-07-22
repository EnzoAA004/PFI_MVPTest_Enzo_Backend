package ar.edu.uade.pfi.backend.service;

import ar.edu.uade.pfi.backend.dto.MultiplanarRunRequestDto;
import ar.edu.uade.pfi.backend.dto.MultiplanarRunResponseDto;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.springframework.stereotype.Service;

@Service
public class MultiplanarRealBaselineContractValidator {
    static final String SAGITTAL_MODEL_KEY = "sagittal_spider";
    static final String AXIAL_MODEL_KEY = "axial_t2_alkafri";
    static final String SAGITTAL_MODEL_VERSION = "sagittal-spider-final-v1";
    static final String SAGITTAL_ARTIFACT_HASH = "cf11dcc0ad77a7c787e64a796a2fd7398ef906add461cef4b3d61f1a5238e944";

    public boolean isStrict(MultiplanarRunRequestDto request) {
        if (request == null || request.metadata() == null) return Boolean.FALSE.equals(request == null ? null : request.allowContractFallback());
        String inferenceMode = text(request.metadata().get("inferenceMode"));
        Object metadataFallback = request.metadata().get("allowContractFallback");
        boolean fallbackFalse = Boolean.FALSE.equals(request.allowContractFallback()) || Boolean.FALSE.equals(metadataFallback);
        return "real_baseline".equals(inferenceMode) && fallbackFalse;
    }

    public void validate(MultiplanarRunRequestDto request, MultiplanarRunResponseDto response) {
        if (!isStrict(request)) return;
        require(response != null, "respuesta multiplanar vacia");
        requireEquals("multiplanar_run_ready", response.status(), "status root");
        requireNotBlank(response.runId(), "runId root");
        requireEquals(request.caseId(), response.caseId(), "caseId root");
        requireEquals("real_baseline", response.requestedInferenceMode(), "requestedInferenceMode root");
        requireEquals("real_baseline", response.effectiveInferenceMode(), "effectiveInferenceMode root");
        requireTrue(Boolean.TRUE.equals(response.humanReviewRequired()), "humanReviewRequired root debe ser true");
        requireTrue(Boolean.TRUE.equals(response.notClinicalDiagnosis()), "notClinicalDiagnosis root debe ser true");
        requireTrue(!Boolean.TRUE.equals(response.degradedMode()), "degradedMode root no puede ser true");
        require(response.planes() != null, "planes es obligatorio");

        validateSagittal(request, response.planes().sagittal());
        validateAxial(request, response.planes().axial());
    }

    public void validateSagittal(MultiplanarRunRequestDto request, MultiplanarRunResponseDto.PlaneDto plane) {
        require(plane != null, "plano sagittal obligatorio");
        requireEquals("sagittal", canonicalPlane(plane.plane()), "plane sagittal");
        requireEquals(SAGITTAL_MODEL_KEY, plane.modelKey(), "modelKey sagittal");
        requireEquals(SAGITTAL_MODEL_VERSION, plane.modelVersion(), "modelVersion sagittal");
        requireEquals(SAGITTAL_ARTIFACT_HASH, artifactHash(plane), "artifactHash sagittal");
        requireEquals("real_baseline", plane.normalizedEffectiveInferenceMode(), "effectiveInferenceMode sagittal");
        requireEquals("real_baseline", textOr(plane.inferenceMode(), plane.normalizedEffectiveInferenceMode()), "inferenceMode sagittal");
        requireEquals("real_baseline", plane.requestedInferenceMode(), "requestedInferenceMode sagittal");
        requireTrue(Boolean.FALSE.equals(plane.allowContractFallback()), "allowContractFallback sagittal debe ser false");
        if (!blank(plane.inputId())) requireEquals(request.sagittalInputId(), plane.inputId(), "inputId sagittal");
        requireEquals("real_baseline", textFrom(plane.aiOutput(), "inferenceMode"), "aiOutput.inferenceMode sagittal");
        requireEquals(SAGITTAL_ARTIFACT_HASH, textFrom(plane.aiOutput(), "artifactHash"), "aiOutput.artifactHash sagittal");
        requireTrue(Boolean.TRUE.equals(boolFrom(plane.aiOutput(), "realInferenceAvailable")), "aiOutput.realInferenceAvailable sagittal debe ser true");
        requireTrue(Boolean.TRUE.equals(plane.humanReviewRequired()), "humanReviewRequired sagittal debe ser true");
        requireTrue(Boolean.TRUE.equals(plane.notClinicalDiagnosis()), "notClinicalDiagnosis sagittal debe ser true");
        requireEquals("real_baseline_ready", textFrom(plane.measurements(), "status"), "measurements.status sagittal");
        requireTrue(plane.series() != null && !plane.series().isEmpty(), "series sagittal no puede estar vacia");
        requireAsset(plane, "input.png");
        requireAsset(plane, "overlay.png");
        validateSagittalOrientation(plane.metadata());
    }

    public void validateAxial(MultiplanarRunRequestDto request, MultiplanarRunResponseDto.PlaneDto plane) {
        require(plane != null, "plano axial obligatorio");
        requireEquals("axial", canonicalPlane(plane.plane()), "plane axial");
        requireEquals(AXIAL_MODEL_KEY, plane.modelKey(), "modelKey axial");
        requireEquals("real_baseline", plane.normalizedEffectiveInferenceMode(), "effectiveInferenceMode axial");
        requireTrue(Boolean.FALSE.equals(plane.allowContractFallback()), "allowContractFallback axial debe ser false");
        if (!blank(plane.inputId())) requireEquals(request.axialInputId(), plane.inputId(), "inputId axial");
        requireTrue(Boolean.TRUE.equals(boolFrom(plane.aiOutput(), "realInferenceAvailable")), "aiOutput.realInferenceAvailable axial debe ser true");
        requireTrue(Boolean.TRUE.equals(plane.humanReviewRequired()), "humanReviewRequired axial debe ser true");
        requireTrue(Boolean.TRUE.equals(plane.notClinicalDiagnosis()), "notClinicalDiagnosis axial debe ser true");
    }

    private void validateSagittalOrientation(Map<String, Object> metadata) {
        if (metadata == null) return;
        if (listEquals(metadata.get("inputShapeNative"), List.of(17, 512, 512))) {
            requireTrue(listEquals(metadata.get("inputShapeCanonical"), List.of(512, 512, 17)), "inputShapeCanonical sagittal invalido");
            requireEquals("move_axis_0_to_last", text(metadata.get("inputOrientationTransform")), "inputOrientationTransform sagittal");
            requireNumberEquals(2, metadata.get("selectedAxis"), "selectedAxis sagittal");
            requireNumberEquals(17, metadata.get("sliceCount"), "sliceCount sagittal");
            int selectedSlice = intValue(metadata.get("selectedSlice"), -1);
            requireTrue(selectedSlice >= 0 && selectedSlice < 17, "selectedSlice sagittal fuera de rango");
        }
        Object spacing = metadata.get("inPlaneSpacing");
        if (spacing instanceof List<?> list) {
            requireTrue(list.size() == 2, "inPlaneSpacing sagittal debe tener dos valores");
            for (Object item : list) {
                requireTrue(doubleValue(item) > 0, "inPlaneSpacing sagittal debe ser positivo");
            }
            requireEquals("mm", text(metadata.get("inPlaneSpacingUnit")), "inPlaneSpacingUnit sagittal");
        }
    }

    private String artifactHash(MultiplanarRunResponseDto.PlaneDto plane) {
        if (!blank(plane.artifactHash())) return plane.artifactHash();
        String fromAi = textFrom(plane.aiOutput(), "artifactHash");
        if (!fromAi.isBlank()) return fromAi;
        if (plane.modelArtifact() == null) return "";
        for (String key : List.of("artifactHash", "sha256", "checkpointHash", "hash")) {
            String value = textFrom(plane.modelArtifact(), key);
            if (!value.isBlank()) return value;
        }
        return "";
    }

    private void requireAsset(MultiplanarRunResponseDto.PlaneDto plane, String assetName) {
        require(plane.assets() != null && plane.assets().containsKey(assetName), "asset " + assetName + " obligatorio en " + plane.plane());
    }

    private boolean listEquals(Object value, List<Integer> expected) {
        if (!(value instanceof List<?> list) || list.size() != expected.size()) return false;
        for (int index = 0; index < expected.size(); index++) {
            if (intValue(list.get(index), Integer.MIN_VALUE) != expected.get(index)) return false;
        }
        return true;
    }

    private Boolean boolFrom(Map<String, Object> map, String key) {
        if (map == null) return null;
        Object value = map.get(key);
        return value instanceof Boolean booleanValue ? booleanValue : Boolean.valueOf(String.valueOf(value));
    }

    private String textFrom(Map<String, Object> map, String key) {
        if (map == null) return "";
        return text(map.get(key));
    }

    private String textOr(String value, String fallback) {
        return blank(value) ? fallback : value.trim();
    }

    private String text(Object value) {
        return value == null ? "" : String.valueOf(value).trim();
    }

    private String canonicalPlane(String plane) {
        String value = text(plane).toLowerCase(Locale.ROOT);
        return "sagital".equals(value) ? "sagittal" : value;
    }

    private void requireEquals(String expected, String actual, String field) {
        if (!text(expected).equals(text(actual))) fail(field + " esperado=" + expected + " actual=" + actual);
    }

    private void requireNumberEquals(int expected, Object actual, String field) {
        if (intValue(actual, Integer.MIN_VALUE) != expected) fail(field + " esperado=" + expected + " actual=" + actual);
    }

    private int intValue(Object value, int fallback) {
        if (value instanceof Number number) return number.intValue();
        try {
            return Integer.parseInt(String.valueOf(value));
        } catch (RuntimeException ex) {
            return fallback;
        }
    }

    private double doubleValue(Object value) {
        if (value instanceof Number number) return number.doubleValue();
        try {
            return Double.parseDouble(String.valueOf(value));
        } catch (RuntimeException ex) {
            return -1;
        }
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
