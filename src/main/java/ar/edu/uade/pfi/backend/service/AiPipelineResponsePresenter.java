package ar.edu.uade.pfi.backend.service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Component;

@Component
public class AiPipelineResponsePresenter {
    private static final Set<String> PUBLIC_ASSETS = Set.of("input.png", "overlay.png", "mask-preview.png");
    private static final Set<String> SENSITIVE_KEYS = Set.of(
        "inputPath",
        "input_path",
        "sourcePath",
        "source_path",
        "imagePath",
        "image_path",
        "outputFiles",
        "output_files"
    );

    public Map<String, Object> present(Map<String, Object> response) {
        Map<String, Object> sanitized = sanitizeMap(response);
        String runId = text(sanitized.get("runId"));
        String plane = text(sanitized.get("plane"));
        Map<String, String> proxyAssets = proxyAssetUrls(sanitized.get("assets"), runId, plane);
        if (!proxyAssets.isEmpty()) {
            sanitized.put("assets", new LinkedHashMap<>(proxyAssets));
        }
        rewriteTopLevelUrls(sanitized, proxyAssets);
        rewriteSeriesUrls(sanitized.get("series"), proxyAssets);
        return sanitized;
    }

    private void rewriteTopLevelUrls(Map<String, Object> response, Map<String, String> proxyAssets) {
        String overlayUrl = proxyAssets.get("overlay.png");
        if (overlayUrl != null) {
            response.put("overlayUrl", overlayUrl);
            response.put("overlayPath", overlayUrl);
        }
    }

    private void rewriteSeriesUrls(Object seriesValue, Map<String, String> proxyAssets) {
        if (!(seriesValue instanceof List<?> series)) {
            return;
        }
        String inputUrl = proxyAssets.get("input.png");
        String overlayUrl = proxyAssets.get("overlay.png");
        for (Object item : series) {
            if (item instanceof Map<?, ?> map) {
                @SuppressWarnings("unchecked")
                Map<String, Object> typed = (Map<String, Object>) map;
                if (inputUrl != null) {
                    typed.put("imageUrl", inputUrl);
                }
                if (overlayUrl != null) {
                    typed.put("overlayUrl", overlayUrl);
                }
            }
        }
    }

    private Map<String, String> proxyAssetUrls(Object assetsValue, String runId, String plane) {
        Map<String, String> urls = new LinkedHashMap<>();
        if (runId.isBlank() || plane.isBlank()) {
            return urls;
        }
        for (String assetName : registeredAssetNames(assetsValue)) {
            if (PUBLIC_ASSETS.contains(assetName)) {
                urls.put(assetName, "/api/ai/assets/" + runId + "/" + plane + "/" + assetName);
            }
        }
        return urls;
    }

    private List<String> registeredAssetNames(Object assetsValue) {
        List<String> names = new ArrayList<>();
        if (assetsValue instanceof Map<?, ?> map) {
            map.forEach((key, value) -> {
                String name = assetName(key, value);
                if (!name.isBlank()) {
                    names.add(name);
                }
            });
        }
        if (assetsValue instanceof Iterable<?> iterable) {
            iterable.forEach(value -> {
                String name = assetName(null, value);
                if (!name.isBlank()) {
                    names.add(name);
                }
            });
        }
        return names;
    }

    private String assetName(Object key, Object value) {
        if (key != null && PUBLIC_ASSETS.contains(key.toString())) {
            return key.toString();
        }
        if (value instanceof Map<?, ?> map) {
            Object name = map.get("assetName");
            if (name == null) {
                name = map.get("name");
            }
            return name == null ? "" : name.toString();
        }
        if (value instanceof String stringValue && PUBLIC_ASSETS.contains(stringValue)) {
            return stringValue;
        }
        return "";
    }

    private Map<String, Object> sanitizeMap(Map<String, Object> source) {
        Map<String, Object> sanitized = new LinkedHashMap<>();
        source.forEach((key, value) -> {
            if (isSensitiveKey(key) || isBlockedAssetKey(key) || isUnsafePathValue(value)) {
                return;
            }
            sanitized.put(key, sanitizeObject(value));
        });
        return sanitized;
    }

    private Object sanitizeObject(Object value) {
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> sanitized = new LinkedHashMap<>();
            map.forEach((key, nestedValue) -> {
                if (key != null && !isSensitiveKey(key.toString()) && !isBlockedAssetKey(key.toString()) && !isUnsafePathValue(nestedValue)) {
                    sanitized.put(key.toString(), sanitizeObject(nestedValue));
                }
            });
            return sanitized;
        }
        if (value instanceof Iterable<?> iterable) {
            List<Object> sanitized = new ArrayList<>();
            iterable.forEach(item -> {
                if (!isUnsafePathValue(item)) {
                    sanitized.add(sanitizeObject(item));
                }
            });
            return sanitized;
        }
        return value;
    }

    private boolean isSensitiveKey(String key) {
        return SENSITIVE_KEYS.contains(key);
    }

    private boolean isBlockedAssetKey(String key) {
        String normalized = key.toLowerCase(Locale.ROOT);
        return "mask.npy".equals(normalized) || "confidence.npy".equals(normalized);
    }

    private boolean isUnsafePathValue(Object value) {
        if (!(value instanceof String stringValue)) {
            return false;
        }
        String normalized = stringValue.replace('\\', '/').toLowerCase(Locale.ROOT);
        if (normalized.startsWith("/api/ai/assets/") || normalized.startsWith("http://") || normalized.startsWith("https://")) {
            return false;
        }
        return normalized.startsWith("/tmp/")
            || normalized.startsWith("/content/")
            || normalized.contains("models/final")
            || normalized.matches("^[a-z]:/.*")
            || normalized.contains("mask.npy")
            || normalized.contains("confidence.npy");
    }

    private String text(Object value) {
        return value == null ? "" : value.toString().trim();
    }
}
