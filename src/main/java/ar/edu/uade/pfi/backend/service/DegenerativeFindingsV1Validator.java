package ar.edu.uade.pfi.backend.service;

import ar.edu.uade.pfi.backend.dto.DegenerativeFindingLocalizationSourceV1;
import ar.edu.uade.pfi.backend.dto.DegenerativeFindingSeverityV1;
import ar.edu.uade.pfi.backend.dto.DegenerativeFindingTypeV1;
import ar.edu.uade.pfi.backend.dto.DegenerativeFindingsV1Dto;
import java.util.EnumMap;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Component;

@Component
public class DegenerativeFindingsV1Validator {
  public static final String SCHEMA_VERSION = "pfi.degenerative-findings.v1";
  private static final double SUM_TOLERANCE = 0.000001d;

  /**
   * Publico porque es el mismo catalogo que tiene que aplicar quien construye un pedido de
   * clasificacion, no solo quien valida la respuesta. Tenerlo en dos lugares es como se llega a que
   * un nivel se acepte al pedir y se rechace al volver.
   */
  public static final Set<String> VALID_LEVELS =
      Set.of("L1-L2", "L2-L3", "L3-L4", "L4-L5", "L5-S1");

  public void validate(DegenerativeFindingsV1Dto root) {
    if (root == null) return;
    require(SCHEMA_VERSION.equals(root.schemaVersion()), "invalid schemaVersion");
    require(root.findings() != null, "findings must be present");
    for (DegenerativeFindingsV1Dto.DegenerativeFindingV1Dto finding : root.findings()) {
      validateFinding(finding);
    }
  }

  private void validateFinding(DegenerativeFindingsV1Dto.DegenerativeFindingV1Dto finding) {
    require(finding != null, "finding must be present");
    require(hasText(finding.findingId()), "findingId is required");
    require(finding.findingType() != null, "findingType is required");
    require(finding.anatomy() != null, "anatomy is required");
    require(VALID_LEVELS.contains(finding.anatomy().level()), "invalid anatomy.level");
    validateSide(finding);
    validateClassification(finding.classification());
    validateEvaluation(finding.evaluation());
    validateSourceSeries(finding.sourceSeries());
    validateLocalization(finding.localization());
    require(finding.model() != null, "model is required");
    require(hasText(finding.model().modelId()), "model.modelId is required");
    require(hasText(finding.model().modelSha256()), "model.modelSha256 is required");
    require(finding.review() != null, "review is required");
    require(Boolean.TRUE.equals(finding.review().required()), "review.required must be true");
    require(finding.review().status() != null, "review.status is required");
    require(
        Boolean.TRUE.equals(finding.notClinicalDiagnosis()), "notClinicalDiagnosis must be true");
  }

  private void validateSide(DegenerativeFindingsV1Dto.DegenerativeFindingV1Dto finding) {
    if (finding.findingType() == DegenerativeFindingTypeV1.CENTRAL_CANAL_STENOSIS) {
      require(finding.anatomy().side() == null, "central canal findings require side=null");
      return;
    }
    require(finding.anatomy().side() != null, "foraminal and subarticular findings require side");
  }

  private void validateClassification(DegenerativeFindingsV1Dto.Classification classification) {
    require(classification != null, "classification is required");
    require(classification.label() != null, "classification.label is required");
    DegenerativeFindingsV1Dto.Probabilities probabilities = classification.probabilities();
    require(probabilities != null, "classification.probabilities is required");
    Map<DegenerativeFindingSeverityV1, Double> values =
        new EnumMap<>(DegenerativeFindingSeverityV1.class);
    values.put(DegenerativeFindingSeverityV1.NORMAL_MILD, probabilities.normalMild());
    values.put(DegenerativeFindingSeverityV1.MODERATE, probabilities.moderate());
    values.put(DegenerativeFindingSeverityV1.SEVERE, probabilities.severe());
    for (Double value : values.values()) {
      require(
          value != null && Double.isFinite(value) && value >= 0.0d && value <= 1.0d,
          "probabilities must be finite values between 0 and 1");
    }
    double sum = values.values().stream().mapToDouble(Double::doubleValue).sum();
    require(Math.abs(1.0d - sum) <= SUM_TOLERANCE, "probabilities must sum to 1");
    DegenerativeFindingSeverityV1 argmax =
        values.entrySet().stream()
            .max(Map.Entry.comparingByValue())
            .map(Map.Entry::getKey)
            .orElseThrow();
    require(classification.label() == argmax, "classification.label must match argmax probability");
  }

  private void validateEvaluation(DegenerativeFindingsV1Dto.Evaluation evaluation) {
    require(evaluation != null, "evaluation is required");
    require(evaluation.status() != null, "evaluation.status is required");
    require(
        evaluation.reasonCode() == null || hasText(evaluation.reasonCode()),
        "evaluation.reasonCode must be non-empty when present");
  }

  private void validateSourceSeries(DegenerativeFindingsV1Dto.SourceSeries sourceSeries) {
    require(sourceSeries != null, "sourceSeries is required");
    require(sourceSeries.role() != null, "sourceSeries.role is required");
    require(
        sourceSeries.position() != null && sourceSeries.position() >= 0,
        "sourceSeries.position must be non-negative");
  }

  private void validateLocalization(DegenerativeFindingsV1Dto.Localization localization) {
    require(localization != null, "localization is required");
    require(localization.source() != null, "localization.source is required");
    require(localization.researchOnly() != null, "localization.researchOnly is required");
    if (localization.source() == DegenerativeFindingLocalizationSourceV1.EXTERNAL_COORDINATE) {
      require(
          Boolean.TRUE.equals(localization.researchOnly()),
          "external_coordinate requires researchOnly=true");
    }
  }

  private void require(boolean condition, String message) {
    if (!condition) throw new IllegalArgumentException("Invalid degenerativeFindings: " + message);
  }

  private boolean hasText(String value) {
    return value != null && !value.isBlank();
  }
}
