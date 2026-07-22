package ar.edu.uade.pfi.backend.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ar.edu.uade.pfi.backend.dto.MultiplanarRunResponseDto;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Test;

class MultiplanarRunResponsePresenterTest {
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final MultiplanarRunResponsePresenter presenter = new MultiplanarRunResponsePresenter();

    @Test
    void deserializesRealAiModuleContractAndPreservesTechnicalFields() throws Exception {
        MultiplanarRunResponseDto response = fixture();
        MultiplanarRunResponseDto.PlaneDto sagittal = response.planes().sagittal();

        assertEquals("real_baseline", sagittal.inferenceMode());
        assertEquals("real_baseline", sagittal.requestedInferenceMode());
        assertEquals("sagittal-spider-final-v1", sagittal.modelVersion());
        assertEquals(MultiplanarRealBaselineContractValidator.SAGITTAL_ARTIFACT_HASH, sagittal.artifactHash());
        assertEquals("inp_sagittal_001", sagittal.inputId());
        assertFalse(sagittal.series().isEmpty());
        assertFalse(sagittal.masks().isEmpty());
        assertFalse(sagittal.landmarks().isEmpty());
        assertFalse(sagittal.measurements().isEmpty());
        assertFalse(sagittal.quality().isEmpty());
        assertEquals("move_axis_0_to_last", sagittal.metadata().get("inputOrientationTransform"));
        assertEquals(true, response.humanReviewRequired());
        assertEquals(true, response.notClinicalDiagnosis());
    }

    @Test
    void derivesEffectiveInferenceModeWithoutLosingInferenceMode() throws Exception {
        MultiplanarRunResponseDto response = fixture();
        MultiplanarRunResponseDto.PlaneDto upstreamSagittal = response.planes().sagittal();
        assertEquals("real_baseline", upstreamSagittal.inferenceMode());
        assertEquals("real_baseline", upstreamSagittal.normalizedEffectiveInferenceMode());

        MultiplanarRunResponseDto presented = presenter.present(response);
        assertEquals("real_baseline", presented.planes().sagittal().effectiveInferenceMode());
        assertEquals("real_baseline", presented.planes().sagittal().inferenceMode());
        assertEquals("real_baseline", presented.planes().axial().effectiveInferenceMode());
        assertEquals("real_baseline", presented.effectiveInferenceMode());
    }

    @Test
    void sanitizesInternalPathsButPreservesClinicalTechnicalMetadata() throws Exception {
        MultiplanarRunResponseDto presented = presenter.present(fixture());
        String payload = objectMapper.writeValueAsString(presented);

        assertFalse(payload.contains("/tmp"));
        assertFalse(payload.contains("/content"));
        assertFalse(payload.contains("C:\\\\"));
        assertFalse(payload.contains("models/final"));
        assertFalse(payload.contains("sourcePath"));
        assertFalse(payload.contains("outputFiles"));
        assertFalse(payload.contains("\"path\""));
        assertTrue(payload.contains("inp_sagittal_001"));
        assertTrue(payload.contains("inputShapeNative"));
        assertTrue(payload.contains("inputShapeCanonical"));
        assertTrue(payload.contains("inputOrientationTransform"));
        assertTrue(payload.contains("inPlaneSpacing"));
    }

    @Test
    void publishesOnlySafeAssetsUsingPlaneRunIdAndRewritesSeriesUrls() throws Exception {
        MultiplanarRunResponseDto presented = presenter.present(fixture());
        Map<String, Object> sagittalAssets = presented.planes().sagittal().assets();
        Map<String, Object> axialAssets = presented.planes().axial().assets();

        assertEquals("/api/ai/assets/run-sag-001/sagittal/input.png", sagittalAssets.get("input.png"));
        assertEquals("/api/ai/assets/run-sag-001/sagittal/overlay.png", sagittalAssets.get("overlay.png"));
        assertEquals("/api/ai/assets/run-sag-001/sagittal/mask-preview.png", sagittalAssets.get("mask-preview.png"));
        assertFalse(sagittalAssets.containsKey("mask.npy"));
        assertFalse(sagittalAssets.containsKey("confidence.npy"));
        assertEquals("/api/ai/assets/run-ax-001/axial/overlay.png", axialAssets.get("overlay.png"));
        assertFalse(String.valueOf(sagittalAssets.get("overlay.png")).contains("multi-contract-001"));

        Map<String, Object> series = presented.planes().sagittal().series().get(0);
        assertEquals("/api/ai/assets/run-sag-001/sagittal/input.png", series.get("imageUrl"));
        assertEquals("/api/ai/assets/run-sag-001/sagittal/overlay.png", series.get("overlayUrl"));
        assertEquals("/api/ai/assets/run-sag-001/sagittal/overlay.png", series.get("overlayPath"));
    }

    @Test
    void canDeriveModeFromAiOutputWhenDirectFieldsAreAbsent() {
        MultiplanarRunResponseDto.PlaneDto plane = new MultiplanarRunResponseDto.PlaneDto(
            "run-1",
            "CASE-1",
            "sagittal",
            "sagittal_spider",
            null,
            null,
            "completed",
            null,
            null,
            null,
            false,
            "inp-1",
            null,
            Map.of("inferenceMode", "real_baseline"),
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            Map.of("overlay.png", Map.of()),
            Map.of(),
            true,
            true,
            false
        );

        assertEquals("real_baseline", presenter.present(new MultiplanarRunResponseDto(
            "multiplanar_run_ready",
            "v1",
            "multi-1",
            "trace-1",
            "CASE-1",
            "workspace",
            "real_baseline",
            null,
            new MultiplanarRunResponseDto.PlanesDto(plane, null),
            null,
            null,
            null,
            null,
            null,
            true,
            true,
            false
        )).planes().sagittal().effectiveInferenceMode());
    }

    private MultiplanarRunResponseDto fixture() throws Exception {
        String json = Files.readString(Path.of("src/test/resources/contracts/ai-module-multiplanar-real-baseline.json"));
        MultiplanarRunResponseDto response = objectMapper.readValue(json, MultiplanarRunResponseDto.class);
        assertNotNull(response.planes().sagittal());
        return response;
    }
}
