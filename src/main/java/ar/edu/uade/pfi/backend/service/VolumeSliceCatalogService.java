package ar.edu.uade.pfi.backend.service;

import ar.edu.uade.pfi.backend.domain.RunArtifact;
import ar.edu.uade.pfi.backend.domain.MeasurementCorrection;
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

    public Map<String, Object> normalizePlaneInput(Map<String, Object> input, String runId, String plane, List<RunArtifact> artifacts) {
        return normalizePlaneInput(input, runId, plane, artifacts, List.of(), List.of(), List.of());
    }

    public Map<String, Object> normalizePlaneInput(
        Map<String, Object> input,
        String runId,
        String plane,
        List<RunArtifact> artifacts,
        List<Map<String, Object>> measurements,
        List<Map<String, Object>> landmarks
    ) {
        return normalizePlaneInput(input, runId, plane, artifacts, measurements, landmarks, List.of());
    }

    @SuppressWarnings("unchecked")
    public Map<String, Object> normalizePlaneInput(
        Map<String, Object> input,
        String runId,
        String plane,
        List<RunArtifact> artifacts,
        List<Map<String, Object>> measurements,
        List<Map<String, Object>> landmarks,
        List<MeasurementCorrection> corrections
    ) {
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
        Set<String> measurementIds = ids(measurements, List.of("id"));
        Set<String> landmarkIds = ids(landmarks, List.of("id", "landmarkId", "name"));
        boolean validateMeasurementIds = !measurementIds.isEmpty();
        boolean validateLandmarkIds = !landmarkIds.isEmpty();
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
            Map<String, Object> overlay = hasResults
                ? normalizeSliceAsset(item.get("overlayAsset"), runId, plane, "slice-overlay", artifactByName)
                : null;
            RefValidation validMeasurements = hasResults
                ? withExistingDiagnostics(validateRefs(item.get("measurementIds"), measurementIds, validateMeasurementIds), item.get("duplicateMeasurementIds"), item.get("invalidMeasurementIds"))
                : existingDiagnostics(item.get("duplicateMeasurementIds"), item.get("invalidMeasurementIds"));
            RefValidation validLandmarks = hasResults
                ? withExistingDiagnostics(validateRefs(item.get("landmarkIds"), landmarkIds, validateLandmarkIds), item.get("duplicateLandmarkIds"), item.get("invalidLandmarkIds"))
                : existingDiagnostics(item.get("duplicateLandmarkIds"), item.get("invalidLandmarkIds"));
            boolean effectiveHasResults = hasResults && (overlay != null || !validMeasurements.values().isEmpty() || !validLandmarks.values().isEmpty());
            entry.put("hasResults", effectiveHasResults);
            entry.put("overlayAsset", effectiveHasResults ? overlay : null);
            entry.put("measurementIds", effectiveHasResults ? validMeasurements.values() : List.of());
            entry.put("landmarkIds", effectiveHasResults ? validLandmarks.values() : List.of());
            List<Map<String, Object>> sliceCorrections = correctionsForSlice(corrections, plane, index, validMeasurements.values());
            if (!sliceCorrections.isEmpty()) {
                entry.put("corrections", sliceCorrections);
                entry.put("correctionCount", sliceCorrections.size());
            }
            putReferenceStatus(entry, effectiveHasResults, validMeasurements, validLandmarks, overlay);
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

    private RefValidation validateRefs(Object value, Set<String> validIds, boolean validateExistence) {
        List<String> refs = stringList(value);
        if (refs.isEmpty()) return RefValidation.empty();
        List<String> values = new ArrayList<>();
        List<String> duplicates = new ArrayList<>();
        List<String> invalid = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (String ref : refs) {
            if (!seen.add(ref)) {
                duplicates.add(ref);
                continue;
            }
            if (validateExistence && !validIds.contains(ref)) {
                invalid.add(ref);
                continue;
            }
            values.add(ref);
        }
        return new RefValidation(List.copyOf(values), List.copyOf(duplicates), List.copyOf(invalid));
    }

    private RefValidation existingDiagnostics(Object duplicates, Object invalid) {
        return new RefValidation(List.of(), stringList(duplicates), stringList(invalid));
    }

    private RefValidation withExistingDiagnostics(RefValidation validation, Object duplicates, Object invalid) {
        return new RefValidation(
            validation.values(),
            appendDistinct(validation.duplicates(), stringList(duplicates)),
            appendDistinct(validation.invalid(), stringList(invalid))
        );
    }

    private List<String> appendDistinct(List<String> first, List<String> second) {
        if (first.isEmpty() && second.isEmpty()) return List.of();
        List<String> result = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (String value : first) {
            if (seen.add(value)) result.add(value);
        }
        for (String value : second) {
            if (seen.add(value)) result.add(value);
        }
        return List.copyOf(result);
    }

    private void putReferenceStatus(
        Map<String, Object> entry,
        boolean hasResults,
        RefValidation measurements,
        RefValidation landmarks,
        Map<String, Object> overlay
    ) {
        if (!hasResults) {
            entry.put("resultStatus", "no_automatic_results");
        } else if (measurements.clean() && landmarks.clean() && overlay != null) {
            entry.put("resultStatus", "automatic_results_available");
        } else {
            entry.put("resultStatus", "degraded_inconsistent_result_references");
        }
        if (!measurements.duplicates().isEmpty()) entry.put("duplicateMeasurementIds", measurements.duplicates());
        if (!measurements.invalid().isEmpty()) entry.put("invalidMeasurementIds", measurements.invalid());
        if (!landmarks.duplicates().isEmpty()) entry.put("duplicateLandmarkIds", landmarks.duplicates());
        if (!landmarks.invalid().isEmpty()) entry.put("invalidLandmarkIds", landmarks.invalid());
    }

    private List<Map<String, Object>> correctionsForSlice(
        List<MeasurementCorrection> corrections,
        String plane,
        int sliceIndex,
        List<String> measurementIds
    ) {
        if (corrections == null || corrections.isEmpty() || measurementIds.isEmpty()) return List.of();
        List<Map<String, Object>> result = new ArrayList<>();
        for (MeasurementCorrection correction : corrections) {
            if (!measurementIds.contains(correction.measurementId())) continue;
            String correctionPlane = correctionPlane(correction);
            Integer correctionSlice = correctionSliceIndex(correction);
            if (!plane.equals(correctionPlane) || correctionSlice == null || correctionSlice != sliceIndex) continue;
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("measurementId", correction.measurementId());
            item.put("label", correction.label());
            item.put("beforeValue", correction.beforeValue());
            item.put("afterValue", correction.afterValue());
            item.put("comment", correction.comment());
            item.put("createdAt", correction.createdAt().toString());
            result.add(item);
        }
        return result;
    }

    private String correctionPlane(MeasurementCorrection correction) {
        String value = text(correction.afterValue().get("plane"));
        if (!value.isBlank()) return value;
        return text(correction.beforeValue().get("plane"));
    }

    private Integer correctionSliceIndex(MeasurementCorrection correction) {
        Integer value = intValue(correction.afterValue().get("sliceIndex"));
        return value == null ? intValue(correction.beforeValue().get("sliceIndex")) : value;
    }

    private Set<String> ids(List<Map<String, Object>> values, List<String> keys) {
        Set<String> ids = new HashSet<>();
        for (Map<String, Object> value : values == null ? List.<Map<String, Object>>of() : values) {
            for (String key : keys) {
                String id = text(value.get(key));
                if (!id.isBlank()) {
                    ids.add(id);
                    break;
                }
            }
        }
        return ids;
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

    private record RefValidation(List<String> values, List<String> duplicates, List<String> invalid) {
        static RefValidation empty() {
            return new RefValidation(List.of(), List.of(), List.of());
        }

        boolean clean() {
            return duplicates.isEmpty() && invalid.isEmpty();
        }
    }
}
