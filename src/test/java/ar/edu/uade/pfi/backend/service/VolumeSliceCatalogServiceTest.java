package ar.edu.uade.pfi.backend.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ar.edu.uade.pfi.backend.domain.MeasurementCorrection;
import ar.edu.uade.pfi.backend.domain.RunArtifact;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class VolumeSliceCatalogServiceTest {
    private final VolumeSliceCatalogService service = new VolumeSliceCatalogService();

    @Test
    @SuppressWarnings("unchecked")
    void normalizesSeventeenSliceCatalogWithBackendUrlsAndSelectedOverlayOnly() throws Exception {
        Map<String, Object> normalized = service.normalizePlaneInput(catalogInput(17, 8), "run-sag-vol", "sagittal");

        assertEquals("available", normalized.get("volumeCatalogStatus"));
        assertEquals(17, normalized.get("sliceCount"));
        assertEquals(8, normalized.get("selectedSliceIndex"));

        List<Map<String, Object>> slices = (List<Map<String, Object>>) normalized.get("slices");
        assertEquals(17, slices.size());
        for (int index = 0; index < slices.size(); index++) {
            Map<String, Object> slice = slices.get(index);
            assertEquals(index, slice.get("index"));
            assertEquals(index + 1, slice.get("displayIndex"));
            Map<String, Object> preview = (Map<String, Object>) slice.get("previewAsset");
            assertEquals("slice-%03d.png".formatted(index), preview.get("assetName"));
            assertEquals("slice-preview", preview.get("role"));
            assertEquals("image/png", preview.get("contentType"));
            assertEquals("/api/ai/assets/run-sag-vol/sagittal/slice-%03d.png".formatted(index), preview.get("url"));
            if (index == 8) {
                assertTrue(Boolean.TRUE.equals(slice.get("hasResults")));
                assertEquals("slice-008-overlay.png", ((Map<String, Object>) slice.get("overlayAsset")).get("assetName"));
            } else {
                assertFalse(Boolean.TRUE.equals(slice.get("hasResults")));
                assertNull(slice.get("overlayAsset"));
            }
        }

        String json = new ObjectMapper().writeValueAsString(normalized);
        assertFalse(json.contains("relativePath"));
        assertFalse(json.contains("/tmp/"));
        assertFalse(json.contains("http://ai-module"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void rejectsUnsafeOrMismatchedSliceAssetsWithoutFailingTheWholeCatalog() {
        Map<String, Object> input = catalogInput(4, 2);
        List<Map<String, Object>> slices = (List<Map<String, Object>>) input.get("slices");
        ((Map<String, Object>) slices.get(0).get("previewAsset")).put("assetName", "../slice-000.png");
        ((Map<String, Object>) slices.get(1).get("previewAsset")).put("assetName", "http://ai-module/assets/slice-001.png");
        ((Map<String, Object>) slices.get(2).get("previewAsset")).put("contentType", "application/json");
        ((Map<String, Object>) slices.get(3).get("previewAsset")).put("assetName", "folder/slice-003.png");

        Map<String, Object> normalized = service.normalizePlaneInput(input, "run-ax-vol", "axial");
        List<Map<String, Object>> normalizedSlices = (List<Map<String, Object>>) normalized.get("slices");

        assertEquals("available", normalized.get("volumeCatalogStatus"));
        assertEquals(4, normalizedSlices.size());
        assertTrue(normalizedSlices.stream().noneMatch(slice -> slice.containsKey("previewAsset")));
        assertEquals("slice-002-overlay.png", ((Map<String, Object>) normalizedSlices.get(2).get("overlayAsset")).get("assetName"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void skipsDuplicateAndOutOfRangeEntriesAsSeriesIncomplete() {
        Map<String, Object> input = catalogInput(3, 1);
        List<Map<String, Object>> slices = (List<Map<String, Object>>) input.get("slices");
        slices.add(slice(1, true));
        slices.add(slice(99, false));

        Map<String, Object> normalized = service.normalizePlaneInput(input, "run-sag-vol", "sagittal");
        List<Map<String, Object>> normalizedSlices = (List<Map<String, Object>>) normalized.get("slices");

        assertEquals("available", normalized.get("volumeCatalogStatus"));
        assertEquals(List.of(0, 1, 2), normalizedSlices.stream().map(slice -> slice.get("index")).toList());
    }

    @Test
    @SuppressWarnings("unchecked")
    void keepsLegacyRunsWithoutSlicesCompatible() {
        Map<String, Object> input = new LinkedHashMap<>(Map.of("inputId", "input-legacy", "selectedSliceIndex", 3));

        Map<String, Object> normalized = service.normalizePlaneInput(input, "run-legacy", "sagittal");

        assertEquals("input-legacy", normalized.get("inputId"));
        assertFalse(normalized.containsKey("slices"));
        assertFalse(normalized.containsKey("volumeCatalogStatus"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void enrichesReopenedCatalogWithDurableStorageStatusHashAndSize() {
        Map<String, Object> input = catalogInput(2, 1);
        List<RunArtifact> artifacts = List.of(
            artifact("run-sag-vol", "sagittal", "slice-000.png", "stored", 42L, "sha-preview-000"),
            artifact("run-sag-vol", "sagittal", "slice-001.png", "missing", null, null),
            artifact("run-sag-vol", "sagittal", "slice-001-overlay.png", "rejected", null, null)
        );

        Map<String, Object> normalized = service.normalizePlaneInput(input, "run-sag-vol", "sagittal", artifacts);
        List<Map<String, Object>> slices = (List<Map<String, Object>>) normalized.get("slices");
        Map<String, Object> storedPreview = (Map<String, Object>) slices.get(0).get("previewAsset");
        Map<String, Object> missingPreview = (Map<String, Object>) slices.get(1).get("previewAsset");
        Map<String, Object> rejectedOverlay = (Map<String, Object>) slices.get(1).get("overlayAsset");

        assertEquals("stored", storedPreview.get("storageStatus"));
        assertEquals(true, storedPreview.get("available"));
        assertEquals(42L, storedPreview.get("sizeBytes"));
        assertEquals("sha-preview-000", storedPreview.get("sha256"));
        assertEquals("missing", missingPreview.get("storageStatus"));
        assertEquals(false, missingPreview.get("available"));
        assertEquals("rejected", rejectedOverlay.get("storageStatus"));
        assertEquals(false, rejectedOverlay.get("available"));
    }

    @Test
    void extractsOnlyGeneratedValidSliceAssetsForPersistence() {
        Map<String, Object> input = catalogInput(3, 1);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> slices = (List<Map<String, Object>>) input.get("slices");
        ((Map<String, Object>) slices.get(0).get("previewAsset")).put("generated", false);
        ((Map<String, Object>) slices.get(2).get("previewAsset")).put("assetName", "slice-002-overlay.png");

        List<Map<String, Object>> assets = service.sliceAssetsFromInput(input);

        assertEquals(List.of("slice-000.png", "slice-001.png", "slice-001-overlay.png"), assets.stream().map(asset -> asset.get("assetName")).toList());
        assertEquals(false, assets.get(0).get("generated"));
        assertEquals(true, assets.get(1).get("generated"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void validatesMeasurementAndLandmarkIdsAgainstPlaneResults() {
        Map<String, Object> input = catalogInput(3, 1);
        List<Map<String, Object>> slices = (List<Map<String, Object>>) input.get("slices");
        slices.get(1).put("measurementIds", List.of("canalAreaMm2", "canalAreaMm2", "missingMeasurement"));
        slices.get(1).put("landmarkIds", List.of("lm-canal", "lm-canal", "missingLandmark"));

        Map<String, Object> normalized = service.normalizePlaneInput(
            input,
            "run-sag-vol",
            "sagittal",
            List.of(),
            List.of(Map.of("id", "canalAreaMm2")),
            List.of(Map.of("id", "lm-canal"))
        );
        List<Map<String, Object>> normalizedSlices = (List<Map<String, Object>>) normalized.get("slices");
        Map<String, Object> selected = normalizedSlices.get(1);

        assertEquals(true, selected.get("hasResults"));
        assertEquals(List.of("canalAreaMm2"), selected.get("measurementIds"));
        assertEquals(List.of("lm-canal"), selected.get("landmarkIds"));
        assertEquals(List.of("canalAreaMm2"), selected.get("duplicateMeasurementIds"));
        assertEquals(List.of("missingMeasurement"), selected.get("invalidMeasurementIds"));
        assertEquals(List.of("lm-canal"), selected.get("duplicateLandmarkIds"));
        assertEquals(List.of("missingLandmark"), selected.get("invalidLandmarkIds"));
        assertEquals("degraded_inconsistent_result_references", selected.get("resultStatus"));
        assertTrue(normalizedSlices.stream()
            .filter(slice -> !slice.get("index").equals(1))
            .allMatch(slice -> ((List<?>) slice.get("measurementIds")).isEmpty() && ((List<?>) slice.get("landmarkIds")).isEmpty()));
    }

    @Test
    @SuppressWarnings("unchecked")
    void publishesCorrectionsOnlyOnMatchingPlaneSliceAndMeasurement() {
        Map<String, Object> input = catalogInput(3, 1);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> slices = (List<Map<String, Object>>) input.get("slices");
        slices.get(1).put("measurementIds", List.of("canalAreaMm2"));
        slices.get(1).put("landmarkIds", List.of("lm-canal"));
        MeasurementCorrection correction = new MeasurementCorrection(
            UUID.randomUUID().toString(),
            UUID.randomUUID().toString(),
            "canalAreaMm2",
            "Area del canal",
            Map.of("plane", "sagittal", "sliceIndex", 1, "aiValue", 82.4),
            Map.of("plane", "sagittal", "sliceIndex", 1, "reviewerValue", 85.1),
            "Ajuste profesional.",
            Instant.parse("2026-07-30T12:30:00Z")
        );

        Map<String, Object> normalized = service.normalizePlaneInput(
            input,
            "run-sag-vol",
            "sagittal",
            List.of(),
            List.of(Map.of("id", "canalAreaMm2")),
            List.of(Map.of("id", "lm-canal")),
            List.of(correction)
        );
        List<Map<String, Object>> normalizedSlices = (List<Map<String, Object>>) normalized.get("slices");
        Map<String, Object> selected = normalizedSlices.get(1);
        List<Map<String, Object>> corrections = (List<Map<String, Object>>) selected.get("corrections");

        assertEquals(1, selected.get("correctionCount"));
        assertEquals("canalAreaMm2", corrections.get(0).get("measurementId"));
        assertEquals(85.1, ((Map<String, Object>) corrections.get(0).get("afterValue")).get("reviewerValue"));
        assertFalse(normalizedSlices.get(0).containsKey("corrections"));
        assertFalse(normalizedSlices.get(2).containsKey("corrections"));
    }

    private Map<String, Object> catalogInput(int count, int selectedIndex) {
        Map<String, Object> input = new LinkedHashMap<>();
        input.put("inputId", "input-volume");
        input.put("seriesId", "series-volume");
        input.put("sourceFormat", "mha");
        input.put("sliceCount", count);
        input.put("selectedSliceIndex", selectedIndex);
        input.put("geometryComplete", true);
        List<Map<String, Object>> slices = new ArrayList<>();
        for (int index = 0; index < count; index++) {
            slices.add(slice(index, index == selectedIndex));
        }
        input.put("slices", slices);
        return input;
    }

    private Map<String, Object> slice(int index, boolean hasResults) {
        Map<String, Object> slice = new LinkedHashMap<>();
        slice.put("index", index);
        slice.put("displayIndex", index + 1);
        slice.put("previewAsset", asset("slice-%03d.png".formatted(index), "slice-preview"));
        slice.put("hasResults", hasResults);
        if (hasResults) {
            slice.put("overlayAsset", asset("slice-%03d-overlay.png".formatted(index), "slice-overlay"));
        }
        slice.put("measurementIds", List.of("m-%03d".formatted(index)));
        slice.put("landmarkIds", List.of("l-%03d".formatted(index)));
        return slice;
    }

    private Map<String, Object> asset(String assetName, String role) {
        Map<String, Object> asset = new LinkedHashMap<>();
        asset.put("assetName", assetName);
        asset.put("role", role);
        asset.put("contentType", "image/png");
        asset.put("generated", true);
        asset.put("relativePath", "/assets/private/" + assetName);
        return asset;
    }

    private RunArtifact artifact(String runId, String plane, String assetName, String storageStatus, Long sizeBytes, String sha256) {
        return new RunArtifact(
            UUID.randomUUID().toString(),
            UUID.randomUUID().toString(),
            runId,
            plane,
            assetName,
            "image/png",
            assetName,
            Instant.parse("2026-07-30T12:00:00Z"),
            storageStatus,
            storageStatus.equals("stored") ? "postgres_bytea" : null,
            sizeBytes,
            sha256
        );
    }
}
