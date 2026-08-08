package ar.edu.uade.pfi.backend.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ar.edu.uade.pfi.backend.domain.CanonicalMultiplanarRun;
import ar.edu.uade.pfi.backend.dto.AiMultiplanarV2ResponseDto;
import ar.edu.uade.pfi.backend.dto.DegenerativeFindingsV1Dto;
import ar.edu.uade.pfi.backend.service.DegenerativeFindingsV1Validator;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class DegenerativeFindingsV1ContractTest {
  private final ObjectMapper objectMapper = new ObjectMapper();
  private final DegenerativeFindingsV1Validator validator = new DegenerativeFindingsV1Validator();
  private final AiMultiplanarV2ResponseAdapter adapter =
      new AiMultiplanarV2ResponseAdapter(objectMapper);

  @Test
  void oldV2RunWithoutDegenerativeFindingsRemainsCompatible() throws Exception {
    AiMultiplanarV2ResponseDto response =
        objectMapper.readValue(
            """
            {
              "status": "completed",
              "schemaVersion": "pfi.multiplanar-run.v2",
              "runId": "multi-old",
              "traceId": "trace-old",
              "caseId": "CASE-OLD",
              "requestedInferenceMode": "real_baseline",
              "effectiveInferenceMode": "real_baseline",
              "synthetic": false,
              "governance": {
                "humanReviewRequired": true,
                "notClinicalDiagnosis": true,
                "deidentified": true,
                "diagnosisGenerated": false
              }
            }
            """,
            AiMultiplanarV2ResponseDto.class);

    CanonicalMultiplanarRun canonical = adapter.toCanonical(response);

    assertTrue(canonical.degenerativeFindings().isEmpty());
  }

  @Test
  void emptyDegenerativeFindingsArrayIsPreservedAtRoot() throws Exception {
    CanonicalMultiplanarRun canonical =
        adapter.toCanonical(
            multiplanarRun(
                """
            {
              "schemaVersion": "pfi.degenerative-findings.v1",
              "findings": []
            }
            """));

    assertEquals(
        "pfi.degenerative-findings.v1", canonical.degenerativeFindings().get("schemaVersion"));
    assertEquals(List.of(), canonical.degenerativeFindings().get("findings"));
  }

  @Test
  void acceptsCentralForaminalAndSubarticularValidFindings() throws Exception {
    DegenerativeFindingsV1Dto dto =
        findings(
            """
            %s,
            %s,
            %s,
            %s
            """
                .formatted(
                    finding(
                        "central-1",
                        "central_canal_stenosis",
                        "L4-L5",
                        "null",
                        "moderate",
                        "sagittal_t2",
                        "slice_index",
                        false),
                    finding(
                        "foraminal-left-1",
                        "neural_foraminal_narrowing",
                        "L5-S1",
                        "\"left\"",
                        "severe",
                        "sagittal_t1",
                        "external_coordinate",
                        true),
                    finding(
                        "foraminal-right-1",
                        "neural_foraminal_narrowing",
                        "L4-L5",
                        "\"right\"",
                        "moderate",
                        "sagittal_t1",
                        "slice_index",
                        false),
                    finding(
                        "subarticular-right-1",
                        "subarticular_stenosis",
                        "L3-L4",
                        "\"right\"",
                        "normal_mild",
                        "axial_t2",
                        "model_generated_roi",
                        false)));

    validator.validate(dto);

    assertEquals(4, dto.findings().size());
  }

  @Test
  void rejectsProbabilitiesThatDoNotSumToOne() throws Exception {
    DegenerativeFindingsV1Dto dto =
        findings(
            finding(
                    "bad-sum",
                    "central_canal_stenosis",
                    "L4-L5",
                    "null",
                    "moderate",
                    "sagittal_t2",
                    "slice_index",
                    false)
                .replace("\"moderate\": 0.60", "\"moderate\": 0.40"));

    assertThrows(IllegalArgumentException.class, () -> validator.validate(dto));
  }

  @Test
  void rejectsLabelThatDoesNotMatchArgmax() throws Exception {
    DegenerativeFindingsV1Dto dto =
        findings(
            finding(
                    "bad-label",
                    "central_canal_stenosis",
                    "L4-L5",
                    "null",
                    "moderate",
                    "sagittal_t2",
                    "slice_index",
                    false)
                .replace("\"label\": \"moderate\"", "\"label\": \"normal_mild\""));

    assertThrows(IllegalArgumentException.class, () -> validator.validate(dto));
  }

  @Test
  void rejectsInvalidLateralityAndLevel() throws Exception {
    DegenerativeFindingsV1Dto centralWithSide =
        findings(
            finding(
                "bad-side",
                "central_canal_stenosis",
                "L4-L5",
                "\"left\"",
                "moderate",
                "sagittal_t2",
                "slice_index",
                false));
    DegenerativeFindingsV1Dto foraminalWithoutSide =
        findings(
            finding(
                "bad-side-2",
                "neural_foraminal_narrowing",
                "L4-L5",
                "null",
                "moderate",
                "sagittal_t2",
                "slice_index",
                false));
    DegenerativeFindingsV1Dto invalidLevel =
        findings(
            finding(
                "bad-level",
                "central_canal_stenosis",
                "L6-L7",
                "null",
                "moderate",
                "sagittal_t2",
                "slice_index",
                false));

    assertThrows(IllegalArgumentException.class, () -> validator.validate(centralWithSide));
    assertThrows(IllegalArgumentException.class, () -> validator.validate(foraminalWithoutSide));
    assertThrows(IllegalArgumentException.class, () -> validator.validate(invalidLevel));
  }

  @Test
  void rejectsForbiddenDicomAndPatientIdentifiers() {
    String json =
        """
            {
              "schemaVersion": "pfi.degenerative-findings.v1",
              "findings": [
                {
                  "findingId": "dicom-leak",
                  "findingType": "central_canal_stenosis",
                  "anatomy": {"level": "L4-L5", "side": null},
                  "classification": {"label": "moderate", "probabilities": {"normal_mild": 0.20, "moderate": 0.60, "severe": 0.20}},
                  "evaluation": {"status": "evaluated"},
                  "sourceSeries": {"role": "sagittal_t2", "position": 0, "SeriesInstanceUID": "1.2.3"},
                  "localization": {"source": "slice_index", "researchOnly": false},
                  "model": {"modelId": "model-a", "modelSha256": "sha256-a"},
                  "review": {"required": true, "status": "pending"},
                  "notClinicalDiagnosis": true
                }
              ]
            }
            """;
    String patientJson =
        json.replace("\"SeriesInstanceUID\": \"1.2.3\"", "\"patientId\": \"patient-original\"");

    assertThrows(
        Exception.class, () -> objectMapper.readValue(json, DegenerativeFindingsV1Dto.class));
    assertThrows(
        Exception.class,
        () -> objectMapper.readValue(patientJson, DegenerativeFindingsV1Dto.class));
  }

  @Test
  void rejectsExternalCoordinateWithoutResearchOnlyAndGovernanceFalse() throws Exception {
    DegenerativeFindingsV1Dto externalCoordinate =
        findings(
            finding(
                "bad-external",
                "neural_foraminal_narrowing",
                "L5-S1",
                "\"left\"",
                "severe",
                "sagittal_t1",
                "external_coordinate",
                false));
    DegenerativeFindingsV1Dto reviewNotRequired =
        findings(
            finding(
                    "bad-review",
                    "central_canal_stenosis",
                    "L4-L5",
                    "null",
                    "moderate",
                    "sagittal_t2",
                    "slice_index",
                    false)
                .replace("\"required\": true", "\"required\": false"));
    DegenerativeFindingsV1Dto clinicalDiagnosis =
        findings(
            finding(
                    "bad-clinical",
                    "central_canal_stenosis",
                    "L4-L5",
                    "null",
                    "moderate",
                    "sagittal_t2",
                    "slice_index",
                    false)
                .replace("\"notClinicalDiagnosis\": true", "\"notClinicalDiagnosis\": false"));

    assertThrows(IllegalArgumentException.class, () -> validator.validate(externalCoordinate));
    assertThrows(IllegalArgumentException.class, () -> validator.validate(reviewNotRequired));
    assertThrows(IllegalArgumentException.class, () -> validator.validate(clinicalDiagnosis));
  }

  @Test
  void nonEvaluatedFindingAllowsOptionalReasonCodeAndRejectsEmptyReasonWhenPresent()
      throws Exception {
    DegenerativeFindingsV1Dto evaluated =
        findings(
            finding(
                "eval",
                "subarticular_stenosis",
                "L3-L4",
                "\"right\"",
                "normal_mild",
                "axial_t2",
                "not_available",
                false));
    DegenerativeFindingsV1Dto notEvaluated =
        findings(
            finding(
                    "not-eval",
                    "subarticular_stenosis",
                    "L3-L4",
                    "\"right\"",
                    "normal_mild",
                    "axial_t2",
                    "not_available",
                    false)
                .replace("\"status\": \"evaluated\"", "\"status\": \"not_evaluated\""));
    DegenerativeFindingsV1Dto unsupported =
        findings(
            finding(
                    "unsupported",
                    "subarticular_stenosis",
                    "L3-L4",
                    "\"right\"",
                    "normal_mild",
                    "axial_t2",
                    "not_available",
                    false)
                .replace("\"status\": \"evaluated\"", "\"status\": \"unsupported\""));
    DegenerativeFindingsV1Dto failed =
        findings(
            finding(
                    "failed",
                    "subarticular_stenosis",
                    "L3-L4",
                    "\"right\"",
                    "normal_mild",
                    "axial_t2",
                    "not_available",
                    false)
                .replace("\"status\": \"evaluated\"", "\"status\": \"failed\""));
    DegenerativeFindingsV1Dto withReason =
        findings(
            finding(
                    "not-eval-reason",
                    "subarticular_stenosis",
                    "L3-L4",
                    "\"right\"",
                    "normal_mild",
                    "axial_t2",
                    "not_available",
                    false)
                .replace(
                    "\"status\": \"evaluated\"",
                    "\"status\": \"not_evaluated\", \"reasonCode\": \"series_not_available\""));
    DegenerativeFindingsV1Dto emptyReason =
        findings(
            finding(
                    "not-eval-empty-reason",
                    "subarticular_stenosis",
                    "L3-L4",
                    "\"right\"",
                    "normal_mild",
                    "axial_t2",
                    "not_available",
                    false)
                .replace(
                    "\"status\": \"evaluated\"",
                    "\"status\": \"not_evaluated\", \"reasonCode\": \"\""));

    validator.validate(evaluated);
    validator.validate(notEvaluated);
    validator.validate(unsupported);
    validator.validate(failed);
    validator.validate(withReason);
    assertThrows(IllegalArgumentException.class, () -> validator.validate(emptyReason));
  }

  @Test
  void rejectsNonCanonicalReviewStatus() {
    String nonCanonicalStatus = "cor" + "rected";
    String json =
        """
            {
              "schemaVersion": "pfi.degenerative-findings.v1",
              "findings": [%s]
            }
            """
            .formatted(
                finding(
                        "non-canonical-review",
                        "central_canal_stenosis",
                        "L4-L5",
                        "null",
                        "moderate",
                        "sagittal_t2",
                        "slice_index",
                        false)
                    .replace(
                        "\"status\": \"pending\"",
                        "\"status\": \"%s\"".formatted(nonCanonicalStatus)));

    assertThrows(
        Exception.class, () -> objectMapper.readValue(json, DegenerativeFindingsV1Dto.class));
  }

  @SuppressWarnings("unchecked")
  @Test
  void validFindingsStayAtRootAndNeverBecomeMeasurements() throws Exception {
    CanonicalMultiplanarRun canonical =
        adapter.toCanonical(
            multiplanarRun(
                """
            {
              "schemaVersion": "pfi.degenerative-findings.v1",
              "findings": [%s]
            }
            """
                    .formatted(
                        finding(
                            "central-root",
                            "central_canal_stenosis",
                            "L4-L5",
                            "null",
                            "moderate",
                            "sagittal_t2",
                            "slice_index",
                            false))));

    assertEquals(
        "pfi.degenerative-findings.v1", canonical.degenerativeFindings().get("schemaVersion"));
    assertEquals(
        1, ((List<Map<String, Object>>) canonical.degenerativeFindings().get("findings")).size());
    assertTrue(canonical.planes().isEmpty());
  }

  @SuppressWarnings("unchecked")
  @Test
  void roundTripPreservesAllContractFields() throws Exception {
    CanonicalMultiplanarRun canonical =
        adapter.toCanonical(
            multiplanarRun(
                """
            {
              "schemaVersion": "pfi.degenerative-findings.v1",
              "findings": [%s]
            }
            """
                    .formatted(
                        finding(
                            "round-trip",
                            "neural_foraminal_narrowing",
                            "L5-S1",
                            "\"right\"",
                            "severe",
                            "sagittal_t1",
                            "external_coordinate",
                            true))));

    Map<String, Object> root = canonical.degenerativeFindings();
    Map<String, Object> finding = ((List<Map<String, Object>>) root.get("findings")).get(0);
    Map<String, Object> anatomy = (Map<String, Object>) finding.get("anatomy");
    Map<String, Object> classification = (Map<String, Object>) finding.get("classification");
    Map<String, Object> probabilities = (Map<String, Object>) classification.get("probabilities");
    Map<String, Object> evaluation = (Map<String, Object>) finding.get("evaluation");
    Map<String, Object> sourceSeries = (Map<String, Object>) finding.get("sourceSeries");
    Map<String, Object> localization = (Map<String, Object>) finding.get("localization");
    Map<String, Object> model = (Map<String, Object>) finding.get("model");
    Map<String, Object> review = (Map<String, Object>) finding.get("review");

    assertEquals("pfi.degenerative-findings.v1", root.get("schemaVersion"));
    assertEquals("round-trip", finding.get("findingId"));
    assertEquals("neural_foraminal_narrowing", finding.get("findingType"));
    assertEquals("L5-S1", anatomy.get("level"));
    assertEquals("right", anatomy.get("side"));
    assertEquals("severe", classification.get("label"));
    assertEquals(0.10, probabilities.get("normal_mild"));
    assertEquals(0.20, probabilities.get("moderate"));
    assertEquals(0.70, probabilities.get("severe"));
    assertEquals("evaluated", evaluation.get("status"));
    assertEquals("sagittal_t1", sourceSeries.get("role"));
    assertEquals(0, sourceSeries.get("position"));
    assertEquals("external_coordinate", localization.get("source"));
    assertEquals(true, localization.get("researchOnly"));
    assertEquals("model-a", model.get("modelId"));
    assertEquals("sha256-a", model.get("modelSha256"));
    assertEquals(true, review.get("required"));
    assertEquals("pending", review.get("status"));
    assertEquals(true, finding.get("notClinicalDiagnosis"));
  }

  private AiMultiplanarV2ResponseDto multiplanarRun(String degenerativeFindingsJson)
      throws Exception {
    String json =
        """
            {
              "status": "completed",
              "schemaVersion": "pfi.multiplanar-run.v2",
              "runId": "multi-deg",
              "traceId": "trace-deg",
              "caseId": "CASE-DEG",
              "requestedInferenceMode": "real_baseline",
              "effectiveInferenceMode": "real_baseline",
              "synthetic": false,
              "degenerativeFindings": %s,
              "governance": {
                "humanReviewRequired": true,
                "notClinicalDiagnosis": true,
                "deidentified": true,
                "diagnosisGenerated": false
              }
            }
            """
            .formatted(degenerativeFindingsJson);
    return objectMapper.readValue(json, AiMultiplanarV2ResponseDto.class);
  }

  private DegenerativeFindingsV1Dto findings(String findingsJson) throws Exception {
    return objectMapper.readValue(
        """
            {
              "schemaVersion": "pfi.degenerative-findings.v1",
              "findings": [%s]
            }
            """
            .formatted(findingsJson),
        DegenerativeFindingsV1Dto.class);
  }

  private String finding(
      String id,
      String type,
      String level,
      String side,
      String label,
      String role,
      String localizationSource,
      boolean researchOnly) {
    return """
            {
              "findingId": "%s",
              "findingType": "%s",
              "anatomy": {"level": "%s", "side": %s},
              "classification": {"label": "%s", "probabilities": %s},
              "evaluation": {"status": "evaluated"},
              "sourceSeries": {"role": "%s", "position": 0},
              "localization": {"source": "%s", "researchOnly": %s},
              "model": {"modelId": "model-a", "modelSha256": "sha256-a"},
              "review": {"required": true, "status": "pending"},
              "notClinicalDiagnosis": true
            }
            """
        .formatted(
            id,
            type,
            level,
            side,
            label,
            probabilities(label),
            role,
            localizationSource,
            researchOnly);
  }

  private String probabilities(String label) {
    return switch (label) {
      case "normal_mild" -> "{\"normal_mild\": 0.70, \"moderate\": 0.20, \"severe\": 0.10}";
      case "severe" -> "{\"normal_mild\": 0.10, \"moderate\": 0.20, \"severe\": 0.70}";
      default -> "{\"normal_mild\": 0.20, \"moderate\": 0.60, \"severe\": 0.20}";
    };
  }
}
