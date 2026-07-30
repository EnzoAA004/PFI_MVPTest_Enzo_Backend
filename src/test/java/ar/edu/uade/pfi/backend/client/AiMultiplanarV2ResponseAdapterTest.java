package ar.edu.uade.pfi.backend.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ar.edu.uade.pfi.backend.domain.CanonicalMultiplanarRun;
import ar.edu.uade.pfi.backend.domain.CanonicalPlaneRun;
import ar.edu.uade.pfi.backend.dto.AiMultiplanarV2ResponseDto;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class AiMultiplanarV2ResponseAdapterTest {
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final AiMultiplanarV2ResponseAdapter adapter = new AiMultiplanarV2ResponseAdapter(objectMapper);

    @Test
    void adaptsSagittalOnlyRealBaselineFixtureToCanonicalModel() throws Exception {
        AiMultiplanarV2ResponseDto response;
        try (InputStream json = getClass().getResourceAsStream("/contracts/ai-module-multiplanar-v2-real-baseline.json")) {
            response = objectMapper.readValue(json, AiMultiplanarV2ResponseDto.class);
        }

        CanonicalMultiplanarRun canonical = adapter.toCanonical(response);

        assertEquals("completed", canonical.status());
        assertEquals("pfi.multiplanar-run.v2", canonical.schemaVersion());
        assertEquals("multi-32d66dabd290b661c709", canonical.multiplanarRunId());
        assertEquals("P9A-SPIDER-101-T2", canonical.caseId());
        assertEquals("sagittal_only", canonical.workspaceMode());
        assertFalse(canonical.synthetic());
        assertNull(canonical.fallbackReason());
        assertEquals(java.util.List.of("sagittal"), canonical.requestedPlanes());
        assertEquals(java.util.List.of("sagittal"), canonical.completedPlanes());
        assertNull(canonical.axial());

        assertTrue(canonical.governance().humanReviewRequired());
        assertTrue(canonical.governance().notClinicalDiagnosis());
        assertFalse(canonical.governance().diagnosisGenerated());

        CanonicalPlaneRun sagittal = canonical.sagittal();
        assertEquals("bec20aa91f96c9cd", sagittal.planeRunId());
        assertEquals("sagittal", sagittal.plane());
        assertEquals("real_baseline", sagittal.effectiveInferenceMode());
        assertEquals("sagittal_spider", sagittal.model().get("key"));
        assertEquals("sagittal-spider-final-v1", sagittal.model().get("version"));
        assertEquals("cf11dcc0ad77a7c787e64a796a2fd7398ef906add461cef4b3d61f1a5238e944", sagittal.model().get("artifactHash"));
        assertEquals(3, sagittal.masks().size());
        assertEquals(3, sagittal.landmarks().size());
        assertEquals(9, sagittal.measurements().size());
        assertEquals(4, sagittal.assets().size());
        assertTrue(sagittal.assets().stream().anyMatch(asset -> "mask.npy".equals(asset.get("assetName"))));
        assertEquals(java.util.List.of(352, 384, 17), sagittal.input().get("nativeShape"));
    }

    @Test
    void returnsNullForNullResponse() {
        assertEquals(null, adapter.toCanonical(null));
    }

    @Test
    @SuppressWarnings("unchecked")
    void adaptsV2VolumeSliceCatalogToCanonicalPlaneInput() throws Exception {
        Map<String, Object> responseMap = new LinkedHashMap<>();
        responseMap.put("status", "completed");
        responseMap.put("schemaVersion", "pfi.multiplanar-run.v2");
        responseMap.put("runId", "multi-volume-v2");
        responseMap.put("traceId", "trace-volume-v2");
        responseMap.put("caseId", "CASE-VOLUME-V2");
        responseMap.put("workspaceMode", "sagittal_only");
        responseMap.put("requestedInferenceMode", "real_baseline");
        responseMap.put("effectiveInferenceMode", "real_baseline");
        responseMap.put("requestedPlanes", List.of("sagittal"));
        responseMap.put("completedPlanes", List.of("sagittal"));
        responseMap.put("governance", Map.of(
            "humanReviewRequired", true,
            "notClinicalDiagnosis", true,
            "deidentified", true,
            "diagnosisGenerated", false
        ));
        responseMap.put("planes", Map.of("sagittal", Map.of(
            "status", "ready",
            "plane", "sagittal",
            "runId", "run-volume-v2",
            "effectiveInferenceMode", "real_baseline",
            "model", Map.of("key", "sagittal_spider", "artifactHash", "sha256:sag"),
            "input", volumeInput(17, 8),
            "assets", List.of(),
            "masks", List.of(),
            "landmarks", List.of(),
            "measurements", List.of()
        )));
        AiMultiplanarV2ResponseDto response = objectMapper.readValue(objectMapper.writeValueAsBytes(responseMap), AiMultiplanarV2ResponseDto.class);

        CanonicalMultiplanarRun canonical = adapter.toCanonical(response);

        Map<String, Object> input = canonical.sagittal().input();
        assertEquals(17, input.get("sliceCount"));
        assertEquals(8, input.get("selectedSliceIndex"));
        assertEquals("available", input.get("volumeCatalogStatus"));
        List<Map<String, Object>> slices = (List<Map<String, Object>>) input.get("slices");
        assertEquals(17, slices.size());
        assertEquals(List.of(0, 1, 2), slices.subList(0, 3).stream().map(slice -> slice.get("index")).toList());
        assertEquals(List.of(1, 2, 3), slices.subList(0, 3).stream().map(slice -> slice.get("displayIndex")).toList());
        Map<String, Object> selected = slices.get(8);
        assertEquals("slice-008-overlay.png", ((Map<String, Object>) selected.get("overlayAsset")).get("assetName"));
        assertEquals("/api/ai/assets/run-volume-v2/sagittal/slice-008.png", ((Map<String, Object>) selected.get("previewAsset")).get("url"));
    }

    private Map<String, Object> volumeInput(int count, int selectedIndex) {
        Map<String, Object> input = new LinkedHashMap<>();
        input.put("inputId", "input-volume-v2");
        input.put("seriesId", "series-volume-v2");
        input.put("sourceFormat", "mha");
        input.put("sliceCount", count);
        input.put("selectedSliceIndex", selectedIndex);
        input.put("selectedAxis", 2);
        input.put("geometryComplete", true);
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
            slice.put("measurementIds", List.of("m-%03d".formatted(index)));
            slice.put("landmarkIds", List.of("l-%03d".formatted(index)));
            slices.add(slice);
        }
        input.put("slices", slices);
        return input;
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
