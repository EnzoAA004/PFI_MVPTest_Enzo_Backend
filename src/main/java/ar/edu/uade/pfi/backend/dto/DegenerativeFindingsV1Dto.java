package ar.edu.uade.pfi.backend.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;

@JsonIgnoreProperties(ignoreUnknown = false)
public record DegenerativeFindingsV1Dto(
    String schemaVersion, List<DegenerativeFindingV1Dto> findings) {
  @JsonIgnoreProperties(ignoreUnknown = false)
  public record DegenerativeFindingV1Dto(
      String findingId,
      DegenerativeFindingTypeV1 findingType,
      Anatomy anatomy,
      Classification classification,
      Evaluation evaluation,
      SourceSeries sourceSeries,
      Localization localization,
      Model model,
      Review review,
      Boolean notClinicalDiagnosis) {}

  @JsonIgnoreProperties(ignoreUnknown = false)
  public record Anatomy(String level, DegenerativeFindingSideV1 side) {}

  @JsonIgnoreProperties(ignoreUnknown = false)
  public record Classification(DegenerativeFindingSeverityV1 label, Probabilities probabilities) {}

  @JsonIgnoreProperties(ignoreUnknown = false)
  public record Probabilities(
      @JsonProperty("normal_mild") Double normalMild, Double moderate, Double severe) {}

  @JsonIgnoreProperties(ignoreUnknown = false)
  public record Evaluation(DegenerativeFindingEvaluationStatusV1 status, String reasonCode) {}

  @JsonIgnoreProperties(ignoreUnknown = false)
  public record SourceSeries(DegenerativeFindingSourceSeriesRoleV1 role, Integer position) {}

  @JsonIgnoreProperties(ignoreUnknown = false)
  public record Localization(
      DegenerativeFindingLocalizationSourceV1 source, Boolean researchOnly) {}

  @JsonIgnoreProperties(ignoreUnknown = false)
  public record Model(String modelId, String modelSha256) {}

  @JsonIgnoreProperties(ignoreUnknown = false)
  public record Review(Boolean required, DegenerativeFindingReviewStatusV1 status) {}
}
