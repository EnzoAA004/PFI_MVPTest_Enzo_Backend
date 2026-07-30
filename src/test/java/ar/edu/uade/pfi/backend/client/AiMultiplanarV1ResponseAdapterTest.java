package ar.edu.uade.pfi.backend.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ar.edu.uade.pfi.backend.domain.CanonicalMultiplanarRun;
import ar.edu.uade.pfi.backend.domain.CanonicalPlaneRun;
import ar.edu.uade.pfi.backend.dto.MultiplanarRunResponseDto;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class AiMultiplanarV1ResponseAdapterTest {
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final AiMultiplanarV1ResponseAdapter adapter = new AiMultiplanarV1ResponseAdapter();

    @Test
    void adaptsDualPlaneFixtureToCanonicalModel() throws Exception {
        String json = Files.readString(Path.of("src/test/resources/contracts/ai-module-multiplanar-real-baseline.json"));
        MultiplanarRunResponseDto response = objectMapper.readValue(json, MultiplanarRunResponseDto.class);

        CanonicalMultiplanarRun canonical = adapter.toCanonical(response);

        assertEquals("multiplanar_run_ready", canonical.status());
        assertEquals("multi-contract-001", canonical.multiplanarRunId());
        assertEquals("CASE-001", canonical.caseId());
        assertTrue(canonical.requestedPlanes().containsAll(java.util.List.of("sagittal", "axial")));
        assertTrue(canonical.completedPlanes().containsAll(java.util.List.of("sagittal", "axial")));
        assertTrue(canonical.governance().humanReviewRequired());
        assertTrue(canonical.governance().notClinicalDiagnosis());

        CanonicalPlaneRun sagittal = canonical.sagittal();
        assertEquals("run-sag-001", sagittal.planeRunId());
        assertEquals("cf11dcc0ad77a7c787e64a796a2fd7398ef906add461cef4b3d61f1a5238e944", sagittal.model().get("artifactHash"));
        assertEquals(1, sagittal.measurements().size());
    }

    @Test
    @SuppressWarnings("unchecked")
    void adaptsLegacyMetadataSliceCatalogToCanonicalPlaneInput() throws Exception {
        Map<String, Object> responseMap = new LinkedHashMap<>();
        responseMap.put("status", "multiplanar_run_ready");
        responseMap.put("schemaVersion", "multiplanar-run-v1");
        responseMap.put("runId", "multi-volume-v1");
        responseMap.put("traceId", "trace-volume-v1");
        responseMap.put("caseId", "CASE-VOLUME-V1");
        responseMap.put("workspaceMode", "sagittal_only");
        responseMap.put("requestedInferenceMode", "real_baseline");
        responseMap.put("effectiveInferenceMode", "real_baseline");
        responseMap.put("humanReviewRequired", true);
        responseMap.put("notClinicalDiagnosis", true);
        Map<String, Object> sagittalPlane = new LinkedHashMap<>();
        sagittalPlane.put("runId", "run-volume-v1");
        sagittalPlane.put("caseId", "CASE-VOLUME-V1");
        sagittalPlane.put("plane", "sagittal");
        sagittalPlane.put("modelKey", "sagittal_spider");
        sagittalPlane.put("artifactHash", "sha256:sag");
        sagittalPlane.put("status", "completed");
        sagittalPlane.put("inferenceMode", "real_baseline");
        sagittalPlane.put("inputId", "input-volume-v1");
        sagittalPlane.put("metadata", legacyMetadata(17, 8));
        sagittalPlane.put("assets", Map.of());
        sagittalPlane.put("measurements", Map.of("values", List.of()));
        sagittalPlane.put("landmarks", List.of());
        responseMap.put("planes", Map.of("sagittal", sagittalPlane));
        MultiplanarRunResponseDto response = objectMapper.readValue(objectMapper.writeValueAsBytes(responseMap), MultiplanarRunResponseDto.class);

        CanonicalMultiplanarRun canonical = adapter.toCanonical(response);

        Map<String, Object> input = canonical.sagittal().input();
        assertEquals("input-volume-v1", input.get("inputId"));
        assertEquals(17, input.get("sliceCount"));
        assertEquals(8, input.get("selectedSliceIndex"));
        assertEquals("available", input.get("volumeCatalogStatus"));
        assertEquals(List.of(512, 512, 17), input.get("canonicalShape"));
        assertEquals("move_axis_0_to_last", input.get("orientationTransform"));
        assertTrue(input.containsKey("slices"));
        List<Map<String, Object>> slices = (List<Map<String, Object>>) input.get("slices");
        assertEquals(17, slices.size());
        assertEquals("slice-008-overlay.png", ((Map<String, Object>) slices.get(8).get("overlayAsset")).get("assetName"));
        assertEquals("/api/ai/assets/run-volume-v1/sagittal/slice-008.png", ((Map<String, Object>) slices.get(8).get("previewAsset")).get("url"));
        assertTrue(input.keySet().stream().noneMatch(key -> key.toLowerCase().contains("path")));
        assertTrue(input.keySet().stream().noneMatch(key -> key.equals("outputFiles")));
    }

    private Map<String, Object> legacyMetadata(int count, int selectedIndex) {
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("selectedSlice", selectedIndex);
        metadata.put("selectedAxis", 2);
        metadata.put("sliceCount", count);
        metadata.put("inputShapeNative", List.of(17, 512, 512));
        metadata.put("inputShapeCanonical", List.of(512, 512, 17));
        metadata.put("inputOrientationTransform", "move_axis_0_to_last");
        metadata.put("spacingXyz", List.of(0.7, 0.7, 4.0));
        metadata.put("arrayAxisSpacingCanonical", List.of(0.7, 0.7, 4.0));
        metadata.put("inPlaneSpacing", List.of(0.7, 0.7));
        metadata.put("sourcePath", "/tmp/private/input.mha");
        metadata.put("outputFiles", Map.of("slice0", "/tmp/private/slice-000.png"));
        List<Map<String, Object>> slices = new ArrayList<>();
        for (int index = 0; index < count; index++) {
            Map<String, Object> slice = new LinkedHashMap<>();
            slice.put("index", index);
            slice.put("displayIndex", index + 1);
            slice.put("previewAsset", sliceAsset("slice-%03d.png".formatted(index), "slice-preview"));
            slice.put("hasResults", index == selectedIndex);
            if (index == selectedIndex) {
                slice.put("overlayAsset", sliceAsset("slice-%03d-overlay.png".formatted(index), "slice-overlay"));
            }
            slices.add(slice);
        }
        metadata.put("slices", slices);
        return metadata;
    }

    private Map<String, Object> sliceAsset(String assetName, String role) {
        return Map.of(
            "assetName", assetName,
            "role", role,
            "contentType", "image/png",
            "generated", true,
            "relativePath", "/assets/private/" + assetName
        );
    }
}
