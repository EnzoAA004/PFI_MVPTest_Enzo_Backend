package ar.edu.uade.pfi.backend.service;

import ar.edu.uade.pfi.backend.dto.MultiplanarRunResponseDto;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.springframework.stereotype.Service;

@Service
public class MultiplanarRunResponsePresenter {
    private static final List<String> PUBLIC_ASSETS = List.of("input.png", "overlay.png", "mask-preview.png");
    private static final List<String> PRIVATE_ASSETS = List.of("mask.npy", "confidence.npy");

    public MultiplanarRunResponseDto present(MultiplanarRunResponseDto response) {
        if (response == null) return null;
        MultiplanarRunResponseDto.PlanesDto planes = response.planes();
        MultiplanarRunResponseDto.PlaneDto sagittal = planes == null ? null : presentPlane(planes.sagittal());
        MultiplanarRunResponseDto.PlaneDto axial = planes == null ? null : presentPlane(planes.axial());
        return new MultiplanarRunResponseDto(
            response.status(),
            response.schemaVersion(),
            response.runId(),
            response.traceId(),
            response.caseId(),
            response.workspaceMode(),
            response.requestedInferenceMode(),
            textOr(response.effectiveInferenceMode(), normalizedWorkspaceMode(sagittal, axial, response.effectiveInferenceMode())),
            planes == null ? null : new MultiplanarRunResponseDto.PlanesDto(sagittal, axial),
            safeMap(response.assets()),
            safeMap(response.threeD()),
            safeMap(response.quality()),
            safeMap(response.review()),
            safeMap(response.metadata()),
            response.humanReviewRequired(),
            response.notClinicalDiagnosis(),
            response.degradedMode()
        );
    }

    public String normalizedEffectiveInferenceMode(MultiplanarRunResponseDto.PlaneDto plane) {
        return plane == null ? null : plane.normalizedEffectiveInferenceMode();
    }

    public Map<String, Object> safeMap(Map<String, Object> source) {
        Object sanitized = sanitizeValue(source);
        if (sanitized instanceof Map<?, ?> map) {
            return castMap(map);
        }
        return source == null ? null : Map.of();
    }

    private MultiplanarRunResponseDto.PlaneDto presentPlane(MultiplanarRunResponseDto.PlaneDto plane) {
        if (plane == null) return null;
        String mode = textOr(plane.effectiveInferenceMode(), plane.normalizedEffectiveInferenceMode());
        Map<String, Object> publicAssets = publicAssets(plane);
        List<Map<String, Object>> series = rewriteSeries(plane.series(), publicAssets);
        return new MultiplanarRunResponseDto.PlaneDto(
            plane.runId(),
            plane.caseId(),
            plane.plane(),
            plane.modelKey(),
            plane.modelVersion(),
            textOr(plane.artifactHash(), artifactHash(plane)),
            plane.status(),
            plane.inferenceMode(),
            plane.requestedInferenceMode(),
            mode,
            plane.allowContractFallback(),
            plane.inputId(),
            safeMap(plane.modelArtifact()),
            safeMap(plane.aiOutput()),
            series,
            safeList(plane.masks()),
            safeList(plane.findings()),
            safeList(plane.landmarks()),
            safeMap(plane.measurements()),
            safeMap(plane.evidence()),
            safeMap(plane.quality()),
            publicAssets,
            safeMap(plane.metadata()),
            plane.humanReviewRequired(),
            plane.notClinicalDiagnosis(),
            plane.degradedMode()
        );
    }

    private List<Map<String, Object>> rewriteSeries(List<Map<String, Object>> series, Map<String, Object> assets) {
        if (series == null) return null;
        List<Map<String, Object>> rewritten = new ArrayList<>();
        String inputUrl = stringValue(assets.get("input.png"));
        String overlayUrl = stringValue(assets.get("overlay.png"));
        for (Map<String, Object> item : series) {
            Map<String, Object> copy = safeMap(item);
            if (!inputUrl.isBlank() && (item.containsKey("imageUrl") || item.containsKey("imagePath"))) {
                copy.put("imageUrl", inputUrl);
            }
            if (!overlayUrl.isBlank() && (item.containsKey("overlayUrl") || item.containsKey("overlayPath"))) {
                copy.put("overlayUrl", overlayUrl);
                copy.put("overlayPath", overlayUrl);
            }
            rewritten.add(copy);
        }
        return rewritten;
    }

    private Map<String, Object> publicAssets(MultiplanarRunResponseDto.PlaneDto plane) {
        Map<String, Object> assets = plane.assets();
        if (assets == null || plane.runId() == null || plane.runId().isBlank()) return Map.of();
        String planeName = canonicalPlane(plane.plane());
        Map<String, Object> published = new LinkedHashMap<>();
        for (String assetName : PUBLIC_ASSETS) {
            if (assetRegistered(assets, assetName)) {
                published.put(assetName, "/api/ai/assets/" + plane.runId() + "/" + planeName + "/" + assetName);
            }
        }
        return published;
    }

    private boolean assetRegistered(Map<String, Object> assets, String assetName) {
        if (assets.containsKey(assetName)) return true;
        return switch (assetName) {
            case "input.png" -> assets.containsKey("input");
            case "overlay.png" -> assets.containsKey("overlay") || assets.containsKey("overlayUrl") || assets.containsKey("overlayPath");
            case "mask-preview.png" -> assets.containsKey("maskPreview");
            default -> false;
        };
    }

    @SuppressWarnings("unchecked")
    private Object sanitizeValue(Object value) {
        if (value == null) return null;
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> sanitized = new LinkedHashMap<>();
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                String key = String.valueOf(entry.getKey());
                Object item = entry.getValue();
                if (dropKey(key, item)) continue;
                Object safe = sanitizeValue(item);
                if (safe != null) sanitized.put(key, safe);
            }
            return sanitized;
        }
        if (value instanceof List<?> list) {
            List<Object> sanitized = new ArrayList<>();
            for (Object item : list) {
                Object safe = sanitizeValue(item);
                if (safe != null) sanitized.add(safe);
            }
            return sanitized;
        }
        if (value instanceof String string && isUnsafePath(string)) {
            return null;
        }
        return value;
    }

    private boolean dropKey(String key, Object value) {
        String normalized = key.toLowerCase(Locale.ROOT);
        if (PRIVATE_ASSETS.contains(key)) return true;
        if (normalized.equals("inputpath") || normalized.equals("input_path")) return true;
        if (normalized.equals("sourcepath") || normalized.equals("source_path")) return true;
        if (normalized.equals("imagepath")) return true;
        if (normalized.equals("outputfiles")) return true;
        if (normalized.equals("path")) return true;
        if (normalized.equals("overlaypath") && value instanceof String string && isUnsafePath(string)) return true;
        return normalized.equals("path") || (value instanceof String string && isUnsafePath(string) && normalized.endsWith("path"));
    }

    private boolean isUnsafePath(String value) {
        String normalized = value.replace('\\', '/').toLowerCase(Locale.ROOT);
        return normalized.startsWith("/tmp")
            || normalized.startsWith("/content")
            || normalized.matches("^[a-z]:/.*")
            || normalized.contains("models/final")
            || normalized.contains("/content/drive")
            || normalized.contains("google drive")
            || normalized.contains("colab");
    }

    private List<Map<String, Object>> safeList(List<Map<String, Object>> source) {
        if (source == null) return null;
        List<Map<String, Object>> safe = new ArrayList<>();
        for (Map<String, Object> item : source) {
            safe.add(safeMap(item));
        }
        return safe;
    }

    private String normalizedWorkspaceMode(MultiplanarRunResponseDto.PlaneDto sagittal, MultiplanarRunResponseDto.PlaneDto axial, String fallback) {
        String sagMode = normalizedEffectiveInferenceMode(sagittal);
        String axMode = normalizedEffectiveInferenceMode(axial);
        if ("real_baseline".equals(sagMode) && "real_baseline".equals(axMode)) return "real_baseline";
        return fallback;
    }

    private String artifactHash(MultiplanarRunResponseDto.PlaneDto plane) {
        String fromAi = textFrom(plane.aiOutput(), "artifactHash");
        if (!fromAi.isBlank()) return fromAi;
        if (plane.modelArtifact() == null) return null;
        for (String key : List.of("artifactHash", "sha256", "checkpointHash", "hash")) {
            String value = textFrom(plane.modelArtifact(), key);
            if (!value.isBlank()) return value;
        }
        return null;
    }

    private String canonicalPlane(String plane) {
        String value = plane == null ? "" : plane.trim().toLowerCase(Locale.ROOT);
        return "sagital".equals(value) ? "sagittal" : value;
    }

    private String textFrom(Map<String, Object> map, String key) {
        if (map == null) return "";
        Object value = map.get(key);
        return value == null ? "" : String.valueOf(value).trim();
    }

    private String stringValue(Object value) {
        return value == null ? "" : String.valueOf(value);
    }

    private String textOr(String primary, String fallback) {
        return primary == null || primary.isBlank() ? fallback : primary.trim();
    }

    private Map<String, Object> castMap(Map<?, ?> map) {
        Map<String, Object> result = new LinkedHashMap<>();
        map.forEach((key, value) -> result.put(String.valueOf(key), value));
        return result;
    }
}
