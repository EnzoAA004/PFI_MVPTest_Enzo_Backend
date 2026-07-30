package ar.edu.uade.pfi.backend.client;

import ar.edu.uade.pfi.backend.domain.CanonicalMultiplanarRun;
import ar.edu.uade.pfi.backend.domain.CanonicalPlaneRun;
import ar.edu.uade.pfi.backend.dto.MultiplanarRunResponseDto;
import ar.edu.uade.pfi.backend.service.VolumeSliceCatalogService;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

@Component
public class AiMultiplanarV1ResponseAdapter {
    private final VolumeSliceCatalogService volumeSliceCatalogService = new VolumeSliceCatalogService();

    public CanonicalMultiplanarRun toCanonical(MultiplanarRunResponseDto response) {
        if (response == null) return null;
        MultiplanarRunResponseDto.PlaneDto sagittal = response.planes() == null ? null : response.planes().sagittal();
        MultiplanarRunResponseDto.PlaneDto axial = response.planes() == null ? null : response.planes().axial();

        Map<String, CanonicalPlaneRun> planes = new LinkedHashMap<>();
        if (sagittal != null) planes.put("sagittal", toPlane(sagittal));
        if (axial != null) planes.put("axial", toPlane(axial));
        List<String> presentPlanes = List.copyOf(planes.keySet());

        return new CanonicalMultiplanarRun(
            text(response.status()),
            text(response.schemaVersion()),
            text(response.runId()),
            text(response.traceId()),
            text(response.caseId()),
            text(response.workspaceMode()),
            text(response.requestedInferenceMode()),
            text(response.effectiveInferenceMode()),
            presentPlanes,
            presentPlanes,
            false,
            null,
            Map.of(),
            planes,
            safe(response.threeD()),
            safe(response.quality()),
            safe(response.review()),
            new CanonicalMultiplanarRun.Governance(
                Boolean.TRUE.equals(response.humanReviewRequired()),
                Boolean.TRUE.equals(response.notClinicalDiagnosis()),
                true,
                false
            )
        );
    }

    private CanonicalPlaneRun toPlane(MultiplanarRunResponseDto.PlaneDto plane) {
        Map<String, Object> input = inputMap(plane);
        input = volumeSliceCatalogService.normalizePlaneInput(input, text(plane.runId()), text(plane.plane()));
        return new CanonicalPlaneRun(
            text(plane.runId()),
            text(plane.plane()),
            text(plane.status()),
            text(plane.normalizedEffectiveInferenceMode()),
            false,
            null,
            modelMap(plane),
            input,
            Map.of(),
            plane.series(),
            assetsList(plane.assets()),
            plane.masks(),
            plane.landmarks(),
            measurementsList(plane.measurements()),
            safe(plane.quality())
        );
    }

    /**
     * v1's inputId lives on the plane DTO itself, while the shape/orientation/slice
     * fields the legacy frontend needs (already legacy-named: inputShapeNative,
     * selectedSlice, sliceCount, ...) live in plane.metadata(). Internal/local paths are
     * excluded here defensively; MultiplanarRunResponsePresenter also sanitizes the final
     * payload downstream.
     */
    private Map<String, Object> inputMap(MultiplanarRunResponseDto.PlaneDto plane) {
        Map<String, Object> input = new LinkedHashMap<>();
        input.put("inputId", text(plane.inputId()));
        if (plane.metadata() != null) {
            plane.metadata().forEach((key, value) -> {
                if (!isInternalMetadataKey(key)) input.put(key, value);
            });
            copyAlias(input, "inputShapeNative", "nativeShape");
            copyAlias(input, "inputShapeCanonical", "canonicalShape");
            copyAlias(input, "inputOrientationTransform", "orientationTransform");
            copyAlias(input, "selectedSlice", "selectedSliceIndex");
            copyAlias(input, "spacingXyz", "spacingXyzMm");
            copyAlias(input, "arrayAxisSpacingCanonical", "canonicalAxisSpacingMm");
            copyAlias(input, "inPlaneSpacing", "inPlaneSpacingMm");
        }
        return input;
    }

    private void copyAlias(Map<String, Object> input, String source, String target) {
        if (input.containsKey(source) && !input.containsKey(target)) {
            input.put(target, input.get(source));
        }
    }

    private boolean isInternalMetadataKey(String key) {
        String normalized = key.toLowerCase(java.util.Locale.ROOT);
        return normalized.equals("sourcepath") || normalized.equals("outputfiles") || normalized.endsWith("path");
    }

    private Map<String, Object> modelMap(MultiplanarRunResponseDto.PlaneDto plane) {
        Map<String, Object> model = new LinkedHashMap<>(safe(plane.modelArtifact()));
        if (!blank(plane.modelKey())) model.put("modelKey", plane.modelKey());
        if (!blank(plane.modelVersion())) model.put("modelVersion", plane.modelVersion());
        String hash = artifactHash(plane);
        if (!blank(hash)) model.put("artifactHash", hash);
        Object availableForRealInference = availableForRealInference(plane);
        if (availableForRealInference != null) model.put("availableForRealInference", availableForRealInference);
        return model;
    }

    /**
     * The v1 contract does not expose model.availableForRealInference directly (that is
     * a v2-only field). We derive it from plane.aiOutput().realInferenceAvailable, which
     * the AI Module already reports for v1 runs, so CanonicalMultiplanarRunLegacyPresenter
     * can compute realInferenceAvailable uniformly for both contract versions.
     */
    private Object availableForRealInference(MultiplanarRunResponseDto.PlaneDto plane) {
        if (plane.aiOutput() != null && plane.aiOutput().containsKey("realInferenceAvailable")) {
            return plane.aiOutput().get("realInferenceAvailable");
        }
        if (plane.modelArtifact() != null && plane.modelArtifact().containsKey("availableForRealInference")) {
            return plane.modelArtifact().get("availableForRealInference");
        }
        return null;
    }

    private String artifactHash(MultiplanarRunResponseDto.PlaneDto plane) {
        if (!blank(plane.artifactHash())) return plane.artifactHash();
        if (plane.modelArtifact() == null) return "";
        for (String key : List.of("artifactHash", "checkpointHash", "hash", "sha256")) {
            Object value = plane.modelArtifact().get(key);
            if (value != null && !String.valueOf(value).isBlank()) return String.valueOf(value);
        }
        return "";
    }

    private boolean blank(String value) {
        return value == null || value.isBlank();
    }

    private List<Map<String, Object>> assetsList(Map<String, Object> assets) {
        if (assets == null) return List.of();
        List<Map<String, Object>> result = new java.util.ArrayList<>();
        assets.forEach((assetName, value) -> {
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("assetName", assetName);
            if (value instanceof Map<?, ?> map) {
                map.forEach((key, val) -> entry.put(String.valueOf(key), val));
            } else if (value != null) {
                entry.put("path", value);
            }
            result.add(entry);
        });
        return result;
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> measurementsList(Map<String, Object> measurements) {
        if (measurements == null) return List.of();
        Object values = measurements.get("values");
        return values instanceof List<?> list ? (List<Map<String, Object>>) list : List.of();
    }

    private Map<String, Object> safe(Map<String, Object> value) {
        return value == null ? Map.of() : value;
    }

    private String text(String value) {
        return value == null ? "" : value;
    }
}
