package ar.edu.uade.pfi.backend.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.List;
import java.util.Map;

@JsonIgnoreProperties(ignoreUnknown = false)
public record DiscDegenerativeFindingsV1Dto(
    DiscDegenerativeFindings discDegenerativeFindings,
    Boolean humanReviewRequired,
    Boolean notClinicalDiagnosis,
    Boolean autonomousDiagnosis
) {
    @JsonIgnoreProperties(ignoreUnknown = false)
    public record DiscDegenerativeFindings(
        String schemaVersion,
        List<Finding> findings
    ) {}

    @JsonIgnoreProperties(ignoreUnknown = false)
    public record Finding(
        String findingId,
        String findingType,
        Anatomy anatomy,
        Classification classification,
        Evidence evidence,
        Evaluation evaluation,
        List<SourceSeries> sourceSeries,
        Localization localization,
        Model model,
        Review review,
        Boolean notClinicalDiagnosis
    ) {}

    @JsonIgnoreProperties(ignoreUnknown = false)
    public record Anatomy(String level, String side) {}

    @JsonIgnoreProperties(ignoreUnknown = false)
    public record Classification(
        String kind,
        String label,
        Map<String, Double> probabilities
    ) {}

    @JsonIgnoreProperties(ignoreUnknown = false)
    public record Evidence(
        String deploymentStatus,
        String evaluationDataset,
        Boolean externalValidationAvailable
    ) {}

    @JsonIgnoreProperties(ignoreUnknown = false)
    public record Evaluation(
        String status,
        String reasonCode
    ) {}

    @JsonIgnoreProperties(ignoreUnknown = false)
    public record SourceSeries(
        String role,
        Boolean available,
        List<Integer> positions
    ) {}

    @JsonIgnoreProperties(ignoreUnknown = false)
    public record Localization(
        String source,
        Boolean researchOnly,
        Boolean automaticAnatomicalLocalizationValidated
    ) {}

    @JsonIgnoreProperties(ignoreUnknown = false)
    public record Model(
        String modelId,
        String modelSha256
    ) {}

    @JsonIgnoreProperties(ignoreUnknown = false)
    public record Review(
        Boolean required,
        String status
    ) {}
}
