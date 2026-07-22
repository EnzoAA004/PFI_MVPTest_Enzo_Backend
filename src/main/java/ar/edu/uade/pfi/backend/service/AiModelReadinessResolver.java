package ar.edu.uade.pfi.backend.service;

import ar.edu.uade.pfi.backend.config.SagittalRealBaselineProperties;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.springframework.stereotype.Component;

@Component
public class AiModelReadinessResolver {
    private static final String AXIAL_MODEL_KEY = "axial_t2_alkafri";
    private final SagittalRealBaselineProperties expected;

    public AiModelReadinessResolver(SagittalRealBaselineProperties expected) {
        this.expected = expected;
    }

    public ModelReadiness resolve(Map<String, Object> verifyModelsResponse) {
        boolean sagittal = isModelReady(verifyModelsResponse, expected.modelKey(), true);
        boolean axial = isModelReady(verifyModelsResponse, AXIAL_MODEL_KEY, false);
        return new ModelReadiness(sagittal, axial);
    }

    private boolean isModelReady(Map<String, Object> response, String modelKey, boolean requireSagittalArtifact) {
        if (containsModel(response.get("missingArtifacts"), modelKey)
            || containsModel(response.get("missingManifestOrBaselineEvidence"), modelKey)
            || containsModel(response.get("unverifiedArtifacts"), modelKey)) {
            return false;
        }
        for (Object item : list(response.get("verifiedModels"))) {
            if (item instanceof Map<?, ?> map) {
                @SuppressWarnings("unchecked")
                Map<String, Object> model = (Map<String, Object>) map;
                if (!modelKey.equals(text(model, "modelKey")) && !modelKey.equals(text(model, "key"))) {
                    continue;
                }
                if (!Boolean.TRUE.equals(model.get("availableForRealInference"))
                    || !Boolean.TRUE.equals(model.get("baselineReady"))
                    || !Boolean.TRUE.equals(model.get("verified"))) {
                    return false;
                }
                if (requireSagittalArtifact) {
                    return expected.modelSha256().equals(text(model, "sha256"))
                        && expected.modelVersion().equals(text(model, "version"));
                }
                return true;
            }
        }
        return false;
    }

    private boolean containsModel(Object value, String modelKey) {
        for (Object item : list(value)) {
            if (item instanceof Map<?, ?> map) {
                @SuppressWarnings("unchecked")
                Map<String, Object> typed = (Map<String, Object>) map;
                if (modelKey.equals(text(typed, "modelKey")) || modelKey.equals(text(typed, "key"))) {
                    return true;
                }
            }
            if (item instanceof String stringValue && modelKey.equals(stringValue)) {
                return true;
            }
        }
        return false;
    }

    private List<?> list(Object value) {
        return value instanceof List<?> list ? list : List.of();
    }

    private String text(Map<String, Object> map, String key) {
        Object value = map.get(key);
        return value == null ? "" : value.toString().trim().toLowerCase(Locale.ROOT).equals("null") ? "" : value.toString().trim();
    }

    public record ModelReadiness(boolean sagittalReadyForRealInference, boolean axialReadyForRealInference) {
    }
}
