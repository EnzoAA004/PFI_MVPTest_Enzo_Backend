package ar.edu.uade.pfi.backend.service;

import ar.edu.uade.pfi.backend.dto.DiscDegenerativeFindingsV1Dto;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

class DiscDegenerativeFindingsV1ValidatorTest {
    private final DiscDegenerativeFindingsV1Validator validator = new DiscDegenerativeFindingsV1Validator();

    @Test
    void acceptsValidDiscBulgingFinding() {
        assertDoesNotThrow(() -> validator.validate(valid("disc_bulging", "binary", "present", Map.of("absent", 0.2, "present", 0.8), "supported_internal")));
    }

    @Test
    void acceptsValidPfirrmannCategoricalFinding() {
        assertDoesNotThrow(() -> validator.validate(valid("pfirrmann_grade", "categorical", "III", Map.of("I", 0.1, "II", 0.1, "III", 0.6, "IV", 0.1, "V", 0.1), "experimental")));
    }

    @Test
    void rejectsLabelThatDoesNotMatchArgmax() {
        DiscDegenerativeFindingsV1Dto payload = valid("disc_bulging", "binary", "absent", Map.of("absent", 0.2, "present", 0.8), "supported_internal");
        assertThrows(IllegalArgumentException.class, () -> validator.validate(payload));
    }

    @Test
    void rejectsDeploymentStatusMismatch() {
        DiscDegenerativeFindingsV1Dto payload = valid("disc_bulging", "binary", "present", Map.of("absent", 0.2, "present", 0.8), "experimental");
        assertThrows(IllegalArgumentException.class, () -> validator.validate(payload));
    }

    private DiscDegenerativeFindingsV1Dto valid(String findingType, String kind, String label, Map<String, Double> probabilities, String deploymentStatus) {
        return new DiscDegenerativeFindingsV1Dto(
            new DiscDegenerativeFindingsV1Dto.DiscDegenerativeFindings(
                DiscDegenerativeFindingsV1Validator.SCHEMA_VERSION,
                List.of(new DiscDegenerativeFindingsV1Dto.Finding(
                    "opaque-id",
                    findingType,
                    new DiscDegenerativeFindingsV1Dto.Anatomy("L4-L5", null),
                    new DiscDegenerativeFindingsV1Dto.Classification(kind, label, probabilities),
                    new DiscDegenerativeFindingsV1Dto.Evidence(deploymentStatus, "SPIDER_internal_test", false),
                    new DiscDegenerativeFindingsV1Dto.Evaluation("evaluated", null),
                    List.of(
                        new DiscDegenerativeFindingsV1Dto.SourceSeries("sagittal_t1", true, List.of(10, 11, 12)),
                        new DiscDegenerativeFindingsV1Dto.SourceSeries("sagittal_t2", false, List.of())
                    ),
                    new DiscDegenerativeFindingsV1Dto.Localization("segmentation_derived_disc_level", true, false),
                    new DiscDegenerativeFindingsV1Dto.Model(
                        "spider_degenerative_multitask_sagittal_t1_t2_2p5d",
                        "16eccff327e6794b127fe372ecd03ea619a0f69d939b84ae1aa2e904191c6293"
                    ),
                    new DiscDegenerativeFindingsV1Dto.Review(true, "pending"),
                    true
                ))
            ),
            true,
            true,
            false
        );
    }
}
