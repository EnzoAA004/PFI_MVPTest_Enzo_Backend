package ar.edu.uade.pfi.backend.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import ar.edu.uade.pfi.backend.domain.CanonicalMultiplanarRun;
import ar.edu.uade.pfi.backend.domain.CanonicalPlaneRun;
import ar.edu.uade.pfi.backend.dto.MultiplanarRunResponseDto;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class CanonicalMultiplanarRunLegacyPresenterTest {
    private static final String SAGITTAL_ARTIFACT_HASH =
        "cf11dcc0ad77a7c787e64a796a2fd7398ef906add461cef4b3d61f1a5238e944";

    private final CanonicalMultiplanarRunLegacyPresenter presenter = new CanonicalMultiplanarRunLegacyPresenter();

    @Test
    void presentsRealBaselineSagittalRunWithLegacyAliasesAndRealAiOutput() {
        CanonicalMultiplanarRun canonical = realBaselineRun();

        MultiplanarRunResponseDto response = presenter.toLegacyResponse(canonical);
        MultiplanarRunResponseDto.PlaneDto plane = response.planes().sagittal();

        assertEquals(true, plane.aiOutput().get("realInferenceAvailable"));
        assertEquals("real_baseline", plane.aiOutput().get("inferenceMode"));
        assertEquals(SAGITTAL_ARTIFACT_HASH, plane.aiOutput().get("artifactHash"));

        assertEquals(List.of(17, 512, 512), plane.metadata().get("inputShapeNative"));
        assertEquals(List.of(512, 512, 17), plane.metadata().get("inputShapeCanonical"));
        assertEquals("move_axis_0_to_last", plane.metadata().get("inputOrientationTransform"));
        assertEquals(8, plane.metadata().get("selectedSlice"));
        assertEquals(17, plane.metadata().get("sliceCount"));
        assertEquals(2, plane.metadata().get("selectedAxis"));
        assertEquals(List.of(0.6875, 0.6875), plane.metadata().get("inPlaneSpacing"));
        assertEquals("mm", plane.metadata().get("inPlaneSpacingUnit"));

        assertEquals("real_baseline", plane.requestedInferenceMode());
        assertEquals(Boolean.FALSE, plane.allowContractFallback());
        assertEquals(Boolean.FALSE, plane.degradedMode());

        assertEquals(Boolean.FALSE, response.degradedMode());
        assertEquals(true, response.humanReviewRequired());
        assertEquals(true, response.notClinicalDiagnosis());
    }

    @Test
    void syntheticFallbackRunIsNeverPresentedAsRealInferenceAvailable() {
        CanonicalMultiplanarRun canonical = syntheticFallbackRun();

        MultiplanarRunResponseDto response = presenter.toLegacyResponse(canonical);
        MultiplanarRunResponseDto.PlaneDto plane = response.planes().sagittal();

        assertEquals(false, plane.aiOutput().get("realInferenceAvailable"));
        assertEquals(Boolean.TRUE, plane.allowContractFallback());
        assertEquals(Boolean.TRUE, plane.degradedMode());
        assertEquals(Boolean.TRUE, response.degradedMode());
        assertFalse("real_baseline_ready".equals(plane.aiOutput().get("status")));
    }

    private CanonicalMultiplanarRun realBaselineRun() {
        Map<String, Object> model = new LinkedHashMap<>();
        model.put("key", "sagittal_spider");
        model.put("version", "sagittal-spider-final-v1");
        model.put("artifactHash", SAGITTAL_ARTIFACT_HASH);
        model.put("baselineReady", true);
        model.put("availableForRealInference", true);
        model.put("manifestValid", true);

        Map<String, Object> input = new LinkedHashMap<>();
        input.put("inputId", "inp_sagittal_001");
        input.put("nativeShape", List.of(17, 512, 512));
        input.put("canonicalShape", List.of(512, 512, 17));
        input.put("orientationTransform", "move_axis_0_to_last");
        input.put("selectedSliceIndex", 8);
        input.put("sliceCount", 17);
        input.put("selectedAxis", 2);
        input.put("inPlaneSpacingMm", List.of(0.6875, 0.6875));

        CanonicalPlaneRun sagittal = new CanonicalPlaneRun(
            "run-sag-001",
            "sagittal",
            "ready",
            "real_baseline",
            false,
            null,
            model,
            input,
            Map.of(),
            List.of(Map.of("seriesId", "series-sag-t2")),
            List.of(Map.of("assetName", "overlay.png")),
            List.of(),
            List.of(),
            List.of(),
            Map.of()
        );

        Map<String, CanonicalPlaneRun> planes = new LinkedHashMap<>();
        planes.put("sagittal", sagittal);

        return new CanonicalMultiplanarRun(
            "completed",
            "pfi.multiplanar-run.v2",
            "multi-001",
            "trace-001",
            "CASE-001",
            "sagittal_only",
            "real_baseline",
            "real_baseline",
            List.of("sagittal"),
            List.of("sagittal"),
            false,
            null,
            Map.of(),
            planes,
            Map.of(),
            Map.of(),
            Map.of(),
            new CanonicalMultiplanarRun.Governance(true, true, true, false)
        );
    }

    private CanonicalMultiplanarRun syntheticFallbackRun() {
        Map<String, Object> model = new LinkedHashMap<>();
        model.put("key", "sagittal_spider");
        model.put("version", "sagittal-spider-final-v1");
        model.put("artifactHash", "fallback-hash");
        model.put("availableForRealInference", false);

        CanonicalPlaneRun sagittal = new CanonicalPlaneRun(
            "run-sag-002",
            "sagittal",
            "fallback_used",
            "contract_fallback",
            true,
            "model_unavailable",
            model,
            Map.of("inputId", "inp_sagittal_002"),
            Map.of(),
            List.of(),
            List.of(),
            List.of(),
            List.of(),
            List.of(),
            Map.of()
        );

        Map<String, CanonicalPlaneRun> planes = new LinkedHashMap<>();
        planes.put("sagittal", sagittal);

        return new CanonicalMultiplanarRun(
            "completed",
            "pfi.multiplanar-run.v2",
            "multi-002",
            "trace-002",
            "CASE-002",
            "sagittal_only",
            "real_baseline",
            "contract_fallback",
            List.of("sagittal"),
            List.of("sagittal"),
            true,
            "model_unavailable",
            Map.of(),
            planes,
            Map.of(),
            Map.of(),
            Map.of(),
            new CanonicalMultiplanarRun.Governance(true, true, true, false)
        );
    }
}
