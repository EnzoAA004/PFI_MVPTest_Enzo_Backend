package ar.edu.uade.pfi.backend.service;

import ar.edu.uade.pfi.backend.domain.RunArtifact;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

public class VolumeSliceCatalogService {

    public Map<String, Object> normalizePlaneInput(Map<String, Object> input, String runId, String plane) {
        return normalizePlaneInput(input, runId, plane, List.of());
    }

    @SuppressWarnings("unchecked")
    public Map<String, Object> normalizePlaneInput(Map<String, Object> input, String runId, String plane, List<RunArtifact> artifacts) {
        if (input == null || input.isEmpty()) return input == null ? Map.of() : input;
        Map<String, Object> normalized = new LinkedHashMap<>(input);
        Object rawSlices = input.get("slices");
        if (!(rawSlices instanceof List<?> rawList)) return normalized;

        Integer sliceCount = intValue(input.get("sliceCount"));
        Integer selectedSlice = intValue(input.get("selectedSliceIndex"));
        if (sliceCount == null || sliceCount <= 0 || selectedSlice == null || selectedSlice < 0 || selectedSlice >= sliceCount) {
            normalized.remove("slices");
            normalized.put("volumeCatalogStatus", "legacy_series");
            return normalized;
        }

        Map<String, RunArtifact> artifactByName = artifactByName(artifacts, runId, plane);
        List<Map<String, Object>> slices = new ArrayList<>();
        Set<Integer> seen = new HashSet<>();
        for (Object value : rawList) {
            if (!(value instanceof Map<?, ?> raw)) continue;
            Map<String, Object> item = castMap(raw);
            Integer index = intValue(item.get("index"));
            if (index == null || index < 0 || index >= sliceCount || !seen.add(index)) continue;
            boolean hasResults = Boolean.TRUE.equals(item.get("hasResults"));
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("index", index);
            entry.put("displayIndex", index + 1);
            Map<String, Object> preview = normalizeSliceAsset(item.get("previewAsset"), runId, plane, "slice-preview", artifactByName);
            if (preview != null) {
                entry.put("previewAsset", preview);
            }
            entry.put("hasResults", hasResults);
            Map<String, Object> overlay = hasResults
                ? normalizeSliceAsset(item.get("overlayAsset"), runId, plane, "slice-overlay", artifactByName)
                : null;
            entry.put("overlayAsset", overlay);
            entry.put("measurementIds", stringList(item.get("measurementIds")));
            entry.put("landmarkIds", stringList(item.get("landmarkIds")));
            slices.add(entry);
        }
        slices.sort(Comparator.comparingInt(item -> ((Number) item.get("index")).intValue()));
        normalized.put("slices", slices);
        normalized.put("volumeCatalogStatus", slices.size() == sliceCount ? "available" : "series_incomplete");
        return normalized;
    }

    public List<Map<String, Object>> sliceAssetsFromInput(Map<String, Object> input) {
        Object rawSlices = input == null ? null : input.get("slices");
        if (!(rawSlices instanceof List<?> slices)) return List.of();
        List<Map<String, Object>> result = new ArrayList<>();
        for (Object value : slices) {
            if (!(value instanceof Map<?, ?> raw)) continue;
            Map<String, Object> item = castMap(raw);
            addSliceAsset(result, item.get("previewAsset"));
            addSliceAsset(result, item.get("overlayAsset"));
        }
        return result;
    }

    private void addSliceAsset(List<Map<String, Object>> result, Object value) {
        if (!(value instanceof Map<?, ?> raw)) return;
        Map<String, Object> asset = castMap(raw);
        String assetName = text(asset.get("assetName"));
        String role = text(asset.get("role"));
        if (!validSliceAsset(assetName, role, text(asset.get("contentType")))) return;
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("assetName", assetName);
        item.put("role", role.replace("-", "_"));
        item.put("contentType", AssetNamePolicy.PNG_CONTENT_TYPE);
        item.put("generated", Boolean.TRUE.equals(asset.get("generated")));
        result.add(item);
    }

    private Map<String, Object> normalizeSliceAsset(Object value, String runId, String plane, String expectedRole, Map<String, RunArtifact> artifactByName) {
        if (!(value instanceof Map<?, ?> raw)) return null;
        Map<String, Object> asset = castMap(raw);
        String assetName = text(asset.get("assetName"));
        if (!validSliceAsset(assetName, expectedRole, text(asset.get("contentType")))) return null;
        Map<String, Object> normalized = new LinkedHashMap<>();
        normalized.put("assetName", assetName);
        normalized.put("role", expectedRole);
        normalized.put("contentType", AssetNamePolicy.PNG_CONTENT_TYPE);
        normalized.put("generated", Boolean.TRUE);
        normalized.put("url", "/api/ai/assets/" + runId + "/" + plane + "/" + assetName);
        RunArtifact artifact = artifactByName.get(assetName);
        if (artifact != null) {
            normalized.put("storageStatus", artifact.storageStatus());
            normalized.put("available", artifact.available());
            if (artifact.sizeBytes() != null) normalized.put("sizeBytes", artifact.sizeBytes());
            if (artifact.sha256() != null && !artifact.sha256().isBlank()) normalized.put("sha256", artifact.sha256());
        }
        return normalized;
    }

    private boolean validSliceAsset(String assetName, String role, String contentType) {
        if (!AssetNamePolicy.PNG_CONTENT_TYPE.equalsIgnoreCase(contentType)) return false;
        if ("slice-preview".equals(role)) return AssetNamePolicy.isSlicePreview(assetName);
        if ("slice-overlay".equals(role)) return AssetNamePolicy.isSliceOverlay(assetName);
        return false;
    }

    private Map<String, RunArtifact> artifactByName(List<RunArtifact> artifacts, String runId, String plane) {
        Map<String, RunArtifact> map = new HashMap<>();
        for (RunArtifact artifact : artifacts == null ? List.<RunArtifact>of() : artifacts) {
            if (runId.equals(artifact.runId()) && plane.equals(artifact.plane())) {
                map.put(artifact.assetName(), artifact);
            }
        }
        return map;
    }

    private List<String> stringList(Object value) {
        if (!(value instanceof List<?> list)) return List.of();
        List<String> result = new ArrayList<>();
        for (Object item : list) {
            if (item != null && !String.valueOf(item).isBlank()) result.add(String.valueOf(item));
        }
        return result;
    }

    private Integer intValue(Object value) {
        if (value instanceof Number number) return number.intValue();
        try {
            return value == null ? null : Integer.parseInt(String.valueOf(value));
        } catch (NumberFormatException ex) {
            return null;
        }
    }

    private Map<String, Object> castMap(Map<?, ?> raw) {
        Map<String, Object> result = new LinkedHashMap<>();
        raw.forEach((key, value) -> {
            if (key != null) result.put(String.valueOf(key), value);
        });
        return result;
    }

    private String text(Object value) {
        return value == null ? "" : String.valueOf(value).trim();
    }
}
