package ar.edu.uade.pfi.backend.service;

import ar.edu.uade.pfi.backend.domain.CanonicalMultiplanarRun;
import ar.edu.uade.pfi.backend.domain.CanonicalPlaneRun;
import ar.edu.uade.pfi.backend.dto.MultiplanarRunResponseDto;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
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
    public MultiplanarRunResponseDto toLegacyResponse(CanonicalMultiplanarRun canonical) {
        if (canonical == null) return null;
        MultiplanarRunResponseDto.PlanesDto planes = new MultiplanarRunResponseDto.PlanesDto(
            toPlaneDto(canonical.sagittal()),
            toPlaneDto(canonical.axial())
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
            canonical.threeD(),
            canonical.quality(),
            canonical.review(),
            Map.of(),
            canonical.governance().humanReviewRequired(),
            canonical.governance().notClinicalDiagnosis(),
            false
        );
    }

    private MultiplanarRunResponseDto.PlaneDto toPlaneDto(CanonicalPlaneRun plane) {
        if (plane == null) return null;
        Map<String, Object> measurements = Map.of("values", plane.measurements());
        return new MultiplanarRunResponseDto.PlaneDto(
            plane.planeRunId(),
            null,
            plane.plane(),
            modelKey(plane.model()),
            modelVersion(plane.model()),
            stringValue(plane.model().get("artifactHash")),
            plane.status(),
            plane.effectiveInferenceMode(),
            null,
            plane.effectiveInferenceMode(),
            false,
            stringValue(plane.input().get("inputId")),
            plane.model(),
            Map.of(),
            plane.series(),
            plane.masks(),
            List.of(),
            plane.landmarks(),
            measurements,
            Map.of(),
            plane.quality(),
            assetsAsMap(plane.assets()),
            plane.input(),
            true,
            true,
            false
        );
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
