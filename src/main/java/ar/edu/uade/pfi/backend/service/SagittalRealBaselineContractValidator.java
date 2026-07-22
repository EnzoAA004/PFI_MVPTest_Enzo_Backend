package ar.edu.uade.pfi.backend.service;

import ar.edu.uade.pfi.backend.config.SagittalRealBaselineProperties;
import ar.edu.uade.pfi.backend.dto.PipelineRunRequestDto;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.springframework.stereotype.Component;

@Component
public class SagittalRealBaselineContractValidator {
    private final SagittalRealBaselineProperties expected;

    public SagittalRealBaselineContractValidator(SagittalRealBaselineProperties expected) {
        this.expected = expected;
    }

    public void validatePipelineResponse(PipelineRunRequestDto request, Map<String, Object> response) {
        requireText(response, "runId");
        requireEquals(request.caseId(), text(response, "caseId"), "caseId");
        requireEquals("sagittal", text(response, "plane"), "plane");
        requireEquals(expected.modelKey(), text(response, "modelKey"), "modelKey");
        requireEquals(expected.modelVersion(), text(response, "modelVersion"), "modelVersion");
        requireEquals(expected.modelSha256(), text(response, "artifactHash"), "artifactHash");
        requireEquals("real_baseline", text(response, "inferenceMode"), "inferenceMode");
        requireEquals("real_baseline", text(response, "requestedInferenceMode"), "requestedInferenceMode");
        requireFalse(response.get("allowContractFallback"), "allowContractFallback");
        requireTrue(response.get("humanReviewRequired"), "humanReviewRequired");
        requireTrue(response.get("notClinicalDiagnosis"), "notClinicalDiagnosis");
        rejectTrue(response.get("degradedMode"), "degradedMode");
        rejectFallbackStatus(response.get("status"));
        rejectPresent(response, "realInferenceFailure");

        Map<String, Object> aiOutput = requireMap(response, "aiOutput");
        requireEquals("real_baseline", text(aiOutput, "inferenceMode"), "aiOutput.inferenceMode");
        requireTrue(aiOutput.get("realInferenceAvailable"), "aiOutput.realInferenceAvailable");
        requireEquals(expected.modelSha256(), text(aiOutput, "artifactHash"), "aiOutput.artifactHash");
        requireTrue(aiOutput.get("humanReviewRequired"), "aiOutput.humanReviewRequired");
        requireTrue(aiOutput.get("notClinicalDiagnosis"), "aiOutput.notClinicalDiagnosis");

        Map<String, Object> metadata = requireMap(response, "metadata");
        requireEquals("real_baseline", text(metadata, "inferenceMode"), "metadata.inferenceMode");
        requireEquals("real_baseline", text(metadata, "requestedInferenceMode"), "metadata.requestedInferenceMode");
        requireEquals(expected.modelSha256(), text(metadata, "artifactHash"), "metadata.artifactHash");
        rejectPresent(metadata, "realInferenceFailure");
        int selectedSlice = requireNonNegativeInt(metadata, "selectedSlice");
        int selectedAxis = requireInt(metadata, "selectedAxis");
        int sliceCount = requirePositiveInt(metadata, "sliceCount");
        if (selectedSlice >= sliceCount) {
            fail("metadata.selectedSlice debe ser menor que metadata.sliceCount");
        }
        List<Object> nativeShape = requireList(metadata, "inputShapeNative");
        List<Object> canonicalShape = requireList(metadata, "inputShapeCanonical");
        requireShape(nativeShape, "metadata.inputShapeNative");
        requireShape(canonicalShape, "metadata.inputShapeCanonical");
        String transform = text(metadata, "inputOrientationTransform");
        if (!"none".equals(transform) && !"move_axis_0_to_last".equals(transform)) {
            fail("metadata.inputOrientationTransform invalido");
        }
        validateSpacing(metadata);
        validateSpiderNativeShape(nativeShape, canonicalShape, transform, selectedAxis, sliceCount);

        validateSeries(response, selectedSlice, sliceCount);
        validateAssets(response, text(response, "runId"), text(response, "plane"));
        validateMeasurements(response);
    }

    public boolean isValidSagittalSync(Map<String, Object> response) {
        try {
            validateSagittalSync(response);
            return true;
        } catch (AiContractViolationException ex) {
            return false;
        }
    }

    public void validateSagittalSync(Map<String, Object> response) {
        String status = text(response, "status");
        if (!"synced_verified".equals(status) && !"existing_release_verified".equals(status)) {
            fail("models/sync no devolvio un estado verificado para el release sagital");
        }
        Map<String, Object> item = findSagittalItem(response);
        requireEquals(expected.modelKey(), text(item, "modelKey"), "modelKey");
        requireEquals("gcs_verified_release", text(item, "source"), "source");
        requireEquals(expected.releaseId(), text(item, "releaseId"), "releaseId");
        requireEquals(expected.releaseContentSha256(), text(item, "releaseContentSha256"), "releaseContentSha256");
        requireEquals(expected.releaseManifestSha256(), text(item, "releaseManifestSha256"), "releaseManifestSha256");
        requireEquals(expected.modelSha256(), text(item, "modelSha256"), "modelSha256");
        requireTrue(item.get("artifactSynced"), "artifactSynced");
        requireTrue(item.get("manifestSynced"), "manifestSynced");
        requireTrue(item.get("modelCardSynced"), "modelCardSynced");
        requireTrue(item.get("releaseMetadataVerified"), "releaseMetadataVerified");
        requireTrue(item.get("gcsReadOnly"), "gcsReadOnly");
        requireZeroOrThree(item.get("filesReplaced"), "filesReplaced");
        requireZeroOrThree(item.get("releaseMetadataReplaced"), "releaseMetadataReplaced");
    }

    private Map<String, Object> findSagittalItem(Object value) {
        if (value instanceof Map<?, ?> map) {
            @SuppressWarnings("unchecked")
            Map<String, Object> typed = (Map<String, Object>) map;
            if (expected.modelKey().equals(text(typed, "modelKey")) || expected.modelKey().equals(text(typed, "key"))) {
                return typed;
            }
            for (Object nested : typed.values()) {
                try {
                    return findSagittalItem(nested);
                } catch (AiContractViolationException ignored) {
                    // Continue searching nested structures.
                }
            }
        }
        if (value instanceof Iterable<?> iterable) {
            for (Object nested : iterable) {
                try {
                    return findSagittalItem(nested);
                } catch (AiContractViolationException ignored) {
                    // Continue searching list items.
                }
            }
        }
        fail("models/sync no incluyo item sagital " + expected.modelKey());
        return Map.of();
    }

    private void validateSeries(Map<String, Object> response, int selectedSlice, int sliceCount) {
        List<Object> series = requireList(response, "series");
        if (series.isEmpty() || !(series.get(0) instanceof Map<?, ?>)) {
            fail("series debe contener al menos una entrada");
        }
        @SuppressWarnings("unchecked")
        Map<String, Object> item = (Map<String, Object>) series.get(0);
        requireEquals("real_baseline_ready", text(item, "status"), "series[0].status");
        requireEquals(sliceCount, intValue(item.get("sliceCount"), "series[0].sliceCount"), "series[0].sliceCount");
        requireEquals(selectedSlice, intValue(item.get("selectedSlice"), "series[0].selectedSlice"), "series[0].selectedSlice");
    }

    private void validateAssets(Map<String, Object> response, String runId, String plane) {
        Map<String, Object> assets = requireMap(response, "assets");
        requireAsset(assets, "input.png", runId, plane);
        requireAsset(assets, "overlay.png", runId, plane);
        rejectAsset(assets, "mask.npy");
        rejectAsset(assets, "confidence.npy");
    }

    private void requireAsset(Map<String, Object> assets, String assetName, String runId, String plane) {
        Object value = assets.get(assetName);
        if (value == null) {
            fail("assets debe contener " + assetName);
        }
        if (value instanceof Map<?, ?> map) {
            @SuppressWarnings("unchecked")
            Map<String, Object> asset = (Map<String, Object>) map;
            requireEquals(runId, text(asset, "runId"), "asset.runId");
            requireEquals(plane, text(asset, "plane"), "asset.plane");
        }
    }

    private void validateMeasurements(Map<String, Object> response) {
        Map<String, Object> measurements = requireMap(response, "measurements");
        requireEquals("real_baseline_ready", text(measurements, "status"), "measurements.status");
        List<Object> values = requireList(measurements, "values");
        for (Object value : values) {
            if (value instanceof Map<?, ?> map) {
                @SuppressWarnings("unchecked")
                Map<String, Object> measurement = (Map<String, Object>) map;
                requireEquals("AI", text(measurement, "source"), "measurements.values.source");
                if (measurement.containsKey("diagnosis") || measurement.containsKey("treatment")) {
                    fail("measurements no debe contener diagnosticos ni tratamientos");
                }
            }
        }
    }

    private void validateSpacing(Map<String, Object> metadata) {
        Object spacing = metadata.get("inPlaneSpacing");
        if (spacing == null) {
            return;
        }
        if (!(spacing instanceof List<?>) || ((List<?>) spacing).size() != 2) {
            fail("metadata.inPlaneSpacing debe ser null o lista de dos numeros positivos");
        }
        for (Object value : (List<?>) spacing) {
            if (!(value instanceof Number number) || number.doubleValue() <= 0) {
                fail("metadata.inPlaneSpacing debe contener numeros positivos");
            }
        }
        Object unit = metadata.get("inPlaneSpacingUnit");
        if (unit != null && !"mm".equals(unit.toString())) {
            fail("metadata.inPlaneSpacingUnit debe ser mm o null");
        }
    }

    private void validateSpiderNativeShape(List<Object> nativeShape, List<Object> canonicalShape, String transform, int selectedAxis, int sliceCount) {
        if (!List.of(17, 512, 512).equals(asInts(nativeShape))) {
            return;
        }
        requireEquals(List.of(512, 512, 17), asInts(canonicalShape), "metadata.inputShapeCanonical");
        requireEquals("move_axis_0_to_last", transform, "metadata.inputOrientationTransform");
        requireEquals(2, selectedAxis, "metadata.selectedAxis");
        requireEquals(17, sliceCount, "metadata.sliceCount");
    }

    private List<Integer> asInts(List<Object> values) {
        return values.stream().map(value -> intValue(value, "shape")).toList();
    }

    private void requireShape(List<Object> shape, String name) {
        if (shape.isEmpty() || shape.stream().anyMatch(value -> !(value instanceof Number))) {
            fail(name + " debe ser una lista numerica valida");
        }
    }

    private Map<String, Object> requireMap(Map<String, Object> map, String key) {
        Object value = map.get(key);
        if (!(value instanceof Map<?, ?>)) {
            fail(key + " es obligatorio");
        }
        @SuppressWarnings("unchecked")
        Map<String, Object> typed = (Map<String, Object>) value;
        return typed;
    }

    private List<Object> requireList(Map<String, Object> map, String key) {
        Object value = map.get(key);
        if (!(value instanceof List<?>)) {
            fail(key + " debe ser una lista");
        }
        return List.copyOf((List<?>) value);
    }

    private String requireText(Map<String, Object> map, String key) {
        String value = text(map, key);
        if (value.isBlank()) {
            fail(key + " es obligatorio");
        }
        return value;
    }

    private String text(Map<String, Object> map, String key) {
        Object value = map.get(key);
        return value == null ? "" : value.toString().trim();
    }

    private int requireNonNegativeInt(Map<String, Object> map, String key) {
        int value = intValue(map.get(key), key);
        if (value < 0) {
            fail(key + " debe ser >= 0");
        }
        return value;
    }

    private int requirePositiveInt(Map<String, Object> map, String key) {
        int value = intValue(map.get(key), key);
        if (value <= 0) {
            fail(key + " debe ser > 0");
        }
        return value;
    }

    private int requireInt(Map<String, Object> map, String key) {
        return intValue(map.get(key), key);
    }

    private int intValue(Object value, String name) {
        if (value instanceof Number number) {
            return number.intValue();
        }
        fail(name + " debe ser entero");
        return -1;
    }

    private void requireTrue(Object value, String name) {
        if (!Boolean.TRUE.equals(value)) {
            fail(name + " debe ser true");
        }
    }

    private void requireFalse(Object value, String name) {
        if (!Boolean.FALSE.equals(value)) {
            fail(name + " debe ser false");
        }
    }

    private void rejectTrue(Object value, String name) {
        if (Boolean.TRUE.equals(value)) {
            fail(name + " no puede ser true");
        }
    }

    private void rejectPresent(Map<String, Object> map, String key) {
        if (map.containsKey(key)) {
            fail(key + " no debe estar presente");
        }
    }

    private void rejectFallbackStatus(Object status) {
        if (status != null && status.toString().toLowerCase(Locale.ROOT).contains("fallback")) {
            fail("status no debe contener fallback");
        }
    }

    private void rejectAsset(Map<String, Object> assets, String assetName) {
        if (assets.containsKey(assetName)) {
            fail("assets no debe publicar " + assetName);
        }
    }

    private void requireZeroOrThree(Object value, String name) {
        int count = intValue(value, name);
        if (count != 0 && count != 3) {
            fail(name + " debe ser 0 o 3");
        }
    }

    private void requireEquals(Object expectedValue, Object actualValue, String name) {
        if (!expectedValue.equals(actualValue)) {
            fail(name + " esperado=" + expectedValue + " actual=" + actualValue);
        }
    }

    private void fail(String message) {
        throw new AiContractViolationException(message);
    }
}
