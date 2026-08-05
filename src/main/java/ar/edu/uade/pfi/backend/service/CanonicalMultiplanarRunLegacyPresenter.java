package ar.edu.uade.pfi.backend.service;

import ar.edu.uade.pfi.backend.domain.CanonicalMultiplanarRun;
import ar.edu.uade.pfi.backend.domain.CanonicalPlaneRun;
import ar.edu.uade.pfi.backend.dto.MultiplanarRunResponseDto;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * Presentation-layer compatibility mapper: converts the internal CanonicalMultiplanarRun
 * (produced by AiServiceClient for either contract version) back into the public
 * MultiplanarRunResponseDto wire shape that the P8 frontend already consumes. This keeps
 * /api/ai/multiplanar/run's JSON contract stable across the v1/v2 rollout — P9-C will
 * migrate the frontend to the canonical shape directly.
 */
@Component
public class CanonicalMultiplanarRunLegacyPresenter {
    private final PublicThreeDAssetPublisher threeDAssetPublisher;

    public CanonicalMultiplanarRunLegacyPresenter() {
        this(new PublicThreeDAssetPublisher());
    }

    @Autowired
    public CanonicalMultiplanarRunLegacyPresenter(PublicThreeDAssetPublisher threeDAssetPublisher) {
        this.threeDAssetPublisher = threeDAssetPublisher;
    }

    public MultiplanarRunResponseDto toLegacyResponse(CanonicalMultiplanarRun canonical) {
        if (canonical == null) return null;
        MultiplanarRunResponseDto.PlanesDto planes = new MultiplanarRunResponseDto.PlanesDto(
            toPlaneDto(canonical.sagittal(), canonical),
            toPlaneDto(canonical.axial(), canonical)
        );
        return new MultiplanarRunResponseDto(
            canonical.status(),
            canonical.schemaVersion(),
            canonical.multiplanarRunId(),
            canonical.traceId(),
            canonical.caseId(),
            canonical.workspaceMode(),
            canonical.requestedInferenceMode(),
            canonical.effectiveInferenceMode(),
            planes,
            Map.of(),
            threeDAssetPublisher.publish(canonical.multiplanarRunId(), canonical.threeD()),
            canonical.quality(),
            canonical.degenerativeFindings(),
            canonical.review(),
            Map.of(),
            canonical.governance().humanReviewRequired(),
            canonical.governance().notClinicalDiagnosis(),
            canonical.synthetic()
        );
    }

    private MultiplanarRunResponseDto.PlaneDto toPlaneDto(CanonicalPlaneRun plane, CanonicalMultiplanarRun root) {
        if (plane == null) return null;
        Map<String, Object> measurements = Map.of("values", plane.measurements());
        boolean allowContractFallback = !isRealBaselineClean(plane);
        return new MultiplanarRunResponseDto.PlaneDto(
            plane.planeRunId(),
            root.caseId(),
            plane.plane(),
            modelKey(plane.model()),
            modelVersion(plane.model()),
            stringValue(plane.model().get("artifactHash")),
            plane.status(),
            plane.effectiveInferenceMode(),
            root.requestedInferenceMode(),
            plane.effectiveInferenceMode(),
            allowContractFallback,
            stringValue(plane.input().get("inputId")),
            plane.model(),
            legacyAiOutput(plane, root),
            plane.series(),
            plane.masks(),
            List.of(),
            plane.landmarks(),
            measurements,
            Map.of(),
            plane.quality(),
            assetsAsMap(plane.assets()),
            legacyMetadata(plane, root, allowContractFallback),
            root.governance().humanReviewRequired(),
            root.governance().notClinicalDiagnosis(),
            plane.synthetic()
        );
    }

    /**
     * A plane counts as a "clean" real_baseline result only when nothing in the
     * canonical run signals a fallback: the effective mode is real_baseline, the plane
     * was not synthesized, and no fallbackReason was recorded.
     */
    private boolean isRealBaselineClean(CanonicalPlaneRun plane) {
        return "real_baseline".equals(plane.effectiveInferenceMode())
            && !plane.synthetic()
            && blank(plane.fallbackReason());
    }

    private boolean realInferenceAvailable(CanonicalPlaneRun plane) {
        return Boolean.TRUE.equals(plane.model().get("availableForRealInference")) && isRealBaselineClean(plane);
    }

    private Map<String, Object> legacyAiOutput(CanonicalPlaneRun plane, CanonicalMultiplanarRun root) {
        boolean realAvailable = realInferenceAvailable(plane);
        Map<String, Object> aiOutput = new LinkedHashMap<>();
        aiOutput.put("status", realAvailable ? "real_baseline_ready" : plane.status());
        aiOutput.put("inferenceMode", plane.effectiveInferenceMode());
        aiOutput.put("requestedInferenceMode", root.requestedInferenceMode());
        aiOutput.put("artifactHash", stringValue(plane.model().get("artifactHash")));
        aiOutput.put("realInferenceAvailable", realAvailable);
        aiOutput.put("modelKey", modelKey(plane.model()));
        aiOutput.put("modelVersion", modelVersion(plane.model()));
        aiOutput.put("synthetic", plane.synthetic());
        aiOutput.put("fallbackReason", blank(plane.fallbackReason()) ? null : plane.fallbackReason());
        aiOutput.put("humanReviewRequired", root.governance().humanReviewRequired());
        aiOutput.put("notClinicalDiagnosis", root.governance().notClinicalDiagnosis());
        return aiOutput;
    }

    private Map<String, Object> legacyMetadata(CanonicalPlaneRun plane, CanonicalMultiplanarRun root, boolean allowContractFallback) {
        Map<String, Object> metadata = new LinkedHashMap<>(plane.input());
        Map<String, Object> input = plane.input();
        putIfPresent(metadata, "inputId", input.get("inputId"));
        putIfPresent(metadata, "inputShapeNative", input.get("nativeShape"));
        putIfPresent(metadata, "inputShapeCanonical", input.get("canonicalShape"));
        putIfPresent(metadata, "inputOrientationTransform", input.get("orientationTransform"));
        putIfPresent(metadata, "selectedSlice", input.get("selectedSliceIndex"));
        putIfPresent(metadata, "sliceCount", input.get("sliceCount"));
        putIfPresent(metadata, "selectedAxis", input.get("selectedAxis"));
        Object inPlaneSpacing = input.get("inPlaneSpacingMm");
        putIfPresent(metadata, "inPlaneSpacing", inPlaneSpacing);
        if (inPlaneSpacing instanceof List<?> list && !list.isEmpty()) {
            metadata.put("inPlaneSpacingUnit", "mm");
        }
        metadata.put("inferenceMode", plane.effectiveInferenceMode());
        metadata.put("requestedInferenceMode", root.requestedInferenceMode());
        metadata.put("allowContractFallback", allowContractFallback);
        metadata.put("synthetic", plane.synthetic());
        metadata.put("fallbackReason", plane.fallbackReason());
        return metadata;
    }

    private void putIfPresent(Map<String, Object> target, String key, Object value) {
        if (value != null) target.put(key, value);
    }

    private Map<String, Object> assetsAsMap(List<Map<String, Object>> assets) {
        Map<String, Object> result = new LinkedHashMap<>();
        for (Map<String, Object> asset : assets) {
            Object assetName = asset.get("assetName");
            if (assetName != null) {
                result.put(String.valueOf(assetName), asset);
            }
        }
        return result;
    }

    private String stringValue(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    private boolean blank(String value) {
        return value == null || value.isBlank();
    }

    /**
     * Model key/version live under different map keys depending on the upstream contract:
     * v2 (AiMultiplanarV2ResponseAdapter) stores the wire names "key"/"version" as-is, while
     * v1 (AiMultiplanarV1ResponseAdapter) stores them as "modelKey"/"modelVersion".
     */
    private String modelKey(Map<String, Object> model) {
        Object value = model.containsKey("key") ? model.get("key") : model.get("modelKey");
        return stringValue(value);
    }

    private String modelVersion(Map<String, Object> model) {
        Object value = model.containsKey("version") ? model.get("version") : model.get("modelVersion");
        return stringValue(value);
    }
}
