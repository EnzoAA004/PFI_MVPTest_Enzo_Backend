package ar.edu.uade.pfi.backend.service;

import ar.edu.uade.pfi.backend.dto.DiscDegenerativeFindingsV1Dto;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Component;

@Component
public class DiscDegenerativeFindingsV1Validator {
    public static final String SCHEMA_VERSION = "pfi.disc-degenerative-findings.v1";
    private static final String MODEL_ID = "spider_degenerative_multitask_sagittal_t1_t2_2p5d";
    private static final double SUM_TOLERANCE = 0.00001d;
    private static final Set<String> LEVELS = Set.of("L1-L2", "L2-L3", "L3-L4", "L4-L5", "L5-S1");
    private static final Set<String> FINDING_TYPES = Set.of(
        "pfirrmann_grade",
        "modic_change",
        "upper_endplate_change",
        "lower_endplate_change",
        "spondylolisthesis",
        "disc_herniation",
        "disc_narrowing",
        "disc_bulging"
    );
    private static final Set<String> REVIEW_STATUSES = Set.of("pending", "accepted", "observed", "rejected", "edited");
    private static final Set<String> LOCALIZATION_SOURCES = Set.of("segmentation_derived_disc_level", "external_disc_roi");
    private static final Set<String> SOURCE_ROLES = Set.of("sagittal_t1", "sagittal_t2");
    private static final Map<String, String> DEPLOYMENT_STATUS = Map.of(
        "upper_endplate_change", "supported_internal",
        "lower_endplate_change", "supported_internal",
        "disc_narrowing", "supported_internal",
        "disc_bulging", "supported_internal",
        "pfirrmann_grade", "experimental",
        "modic_change", "not_product_supported",
        "spondylolisthesis", "not_product_supported",
        "disc_herniation", "not_product_supported"
    );
    private static final Map<String, Set<String>> LABELS = Map.of(
        "pfirrmann_grade", Set.of("I", "II", "III", "IV", "V"),
        "modic_change", Set.of("none", "I", "II", "III"),
        "upper_endplate_change", Set.of("absent", "present"),
        "lower_endplate_change", Set.of("absent", "present"),
        "spondylolisthesis", Set.of("absent", "present"),
        "disc_herniation", Set.of("absent", "present"),
        "disc_narrowing", Set.of("absent", "present"),
        "disc_bulging", Set.of("absent", "present")
    );

    public void validate(DiscDegenerativeFindingsV1Dto root) {
        require(root != null, "root is required");
        require(Boolean.TRUE.equals(root.humanReviewRequired()), "humanReviewRequired must be true");
        require(Boolean.TRUE.equals(root.notClinicalDiagnosis()), "notClinicalDiagnosis must be true");
        require(Boolean.FALSE.equals(root.autonomousDiagnosis()), "autonomousDiagnosis must be false");
        require(root.discDegenerativeFindings() != null, "discDegenerativeFindings is required");
        require(SCHEMA_VERSION.equals(root.discDegenerativeFindings().schemaVersion()), "invalid schemaVersion");
        require(root.discDegenerativeFindings().findings() != null, "findings must be present");
        for (DiscDegenerativeFindingsV1Dto.Finding finding : root.discDegenerativeFindings().findings()) {
            validateFinding(finding);
        }
    }

    private void validateFinding(DiscDegenerativeFindingsV1Dto.Finding finding) {
        require(finding != null, "finding is required");
        require(hasText(finding.findingId()), "findingId is required");
        require(FINDING_TYPES.contains(finding.findingType()), "findingType is not supported");
        require(finding.anatomy() != null && LEVELS.contains(finding.anatomy().level()), "invalid anatomy.level");
        require(finding.anatomy().side() == null, "disc multitask findings require side=null");
        validateClassification(finding.findingType(), finding.classification());
        require(finding.evidence() != null, "evidence is required");
        require(DEPLOYMENT_STATUS.get(finding.findingType()).equals(finding.evidence().deploymentStatus()), "invalid deploymentStatus");
        require("SPIDER_internal_test".equals(finding.evidence().evaluationDataset()), "invalid evaluationDataset");
        require(Boolean.FALSE.equals(finding.evidence().externalValidationAvailable()), "externalValidationAvailable must be false");
        require(finding.evaluation() != null && Set.of("evaluated", "not_evaluated", "unsupported", "failed").contains(finding.evaluation().status()), "invalid evaluation.status");
        validateSourceSeries(finding.sourceSeries());
        require(finding.localization() != null, "localization is required");
        require(LOCALIZATION_SOURCES.contains(finding.localization().source()), "invalid localization.source");
        require(Boolean.TRUE.equals(finding.localization().researchOnly()), "localization.researchOnly must be true");
        require(Boolean.FALSE.equals(finding.localization().automaticAnatomicalLocalizationValidated()), "automatic localization must be false");
        require(finding.model() != null && MODEL_ID.equals(finding.model().modelId()), "invalid modelId");
        require(isSha(finding.model().modelSha256()), "invalid modelSha256");
        require(finding.review() != null && Boolean.TRUE.equals(finding.review().required()), "review.required must be true");
        require(REVIEW_STATUSES.contains(finding.review().status()), "invalid review.status");
        require(Boolean.TRUE.equals(finding.notClinicalDiagnosis()), "notClinicalDiagnosis must be true");
    }

    private void validateClassification(String findingType, DiscDegenerativeFindingsV1Dto.Classification classification) {
        require(classification != null, "classification is required");
        String expectedKind = ("pfirrmann_grade".equals(findingType) || "modic_change".equals(findingType)) ? "categorical" : "binary";
        require(expectedKind.equals(classification.kind()), "classification.kind does not match findingType");
        Set<String> expectedLabels = LABELS.get(findingType);
        require(expectedLabels.contains(classification.label()), "classification.label is not supported");
        require(classification.probabilities() != null && classification.probabilities().keySet().equals(expectedLabels), "probabilities labels mismatch");
        double sum = 0.0d;
        String argmax = null;
        double best = Double.NEGATIVE_INFINITY;
        for (Map.Entry<String, Double> entry : classification.probabilities().entrySet()) {
            Double value = entry.getValue();
            require(value != null && Double.isFinite(value) && value >= 0.0d && value <= 1.0d, "probabilities must be finite values between 0 and 1");
            sum += value;
            if (value > best) {
                best = value;
                argmax = entry.getKey();
            }
        }
        require(Math.abs(1.0d - sum) <= SUM_TOLERANCE, "probabilities must sum to 1");
        require(classification.label().equals(argmax), "classification.label must match argmax probability");
    }

    private void validateSourceSeries(java.util.List<DiscDegenerativeFindingsV1Dto.SourceSeries> sourceSeries) {
        require(sourceSeries != null && !sourceSeries.isEmpty(), "sourceSeries is required");
        boolean anyAvailable = false;
        for (DiscDegenerativeFindingsV1Dto.SourceSeries item : sourceSeries) {
            require(item != null && SOURCE_ROLES.contains(item.role()), "invalid sourceSeries.role");
            require(item.available() != null, "sourceSeries.available is required");
            anyAvailable |= Boolean.TRUE.equals(item.available());
            require(item.positions() != null, "sourceSeries.positions is required");
            for (Integer position : item.positions()) {
                require(position != null && position >= 0, "sourceSeries.positions must be non-negative");
            }
        }
        require(anyAvailable, "at least one modality must be available");
    }

    private boolean isSha(String value) {
        return value != null && value.matches("^[0-9a-f]{64}$");
    }

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    private void require(boolean condition, String message) {
        if (!condition) throw new IllegalArgumentException("Invalid discDegenerativeFindings: " + message);
    }
}
