package ar.edu.uade.pfi.backend.util;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class DiscDegenerativeFindingsPublicProjectionTest {
    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void removesProbabilitiesFromEveryFindingWithoutMutatingTheSource() throws Exception {
        Map<String, Object> source = envelope();
        Map<String, Object> sanitized = DiscDegenerativeFindingsPublicProjection.sanitize(source);

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> sanitizedFindings = (List<Map<String, Object>>) sanitized.get("findings");
        for (Map<String, Object> finding : sanitizedFindings) {
            @SuppressWarnings("unchecked")
            Map<String, Object> classification = (Map<String, Object>) finding.get("classification");
            assertFalse(classification.containsKey("probabilities"), "sanitized output must not contain probabilities");
        }

        // The object passed in is untouched: this is a projection, not a redaction of the
        // persisted/internal object. Internal callers (validation, persistence, audit)
        // must still see the full prediction.
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> sourceFindings = (List<Map<String, Object>>) source.get("findings");
        @SuppressWarnings("unchecked")
        Map<String, Object> sourceClassification = (Map<String, Object>) sourceFindings.get(0).get("classification");
        assertTrue(sourceClassification.containsKey("probabilities"), "source object must not be mutated");
    }

    @Test
    void preservesLabelAndGovernanceAndProvenanceFields() throws Exception {
        Map<String, Object> sanitized = DiscDegenerativeFindingsPublicProjection.sanitize(envelope());
        @SuppressWarnings("unchecked")
        Map<String, Object> finding = ((List<Map<String, Object>>) sanitized.get("findings")).get(0);

        @SuppressWarnings("unchecked")
        Map<String, Object> classification = (Map<String, Object>) finding.get("classification");
        assertEquals("present", classification.get("label"));
        assertEquals("binary", classification.get("kind"));

        assertEquals("finding-1", finding.get("findingId"));
        assertEquals("disc_bulging", finding.get("findingType"));
        assertEquals(true, finding.get("notClinicalDiagnosis"));

        @SuppressWarnings("unchecked")
        Map<String, Object> anatomy = (Map<String, Object>) finding.get("anatomy");
        assertEquals("L4-L5", anatomy.get("level"));

        @SuppressWarnings("unchecked")
        Map<String, Object> evidence = (Map<String, Object>) finding.get("evidence");
        assertEquals("supported_internal", evidence.get("deploymentStatus"));

        assertTrue(finding.containsKey("evaluation"));
        assertTrue(finding.containsKey("sourceSeries"));
        assertTrue(finding.containsKey("localization"));

        @SuppressWarnings("unchecked")
        Map<String, Object> model = (Map<String, Object>) finding.get("model");
        assertEquals("16eccff327e6794b127fe372ecd03ea619a0f69d939b84ae1aa2e904191c6293", model.get("modelSha256"));

        @SuppressWarnings("unchecked")
        Map<String, Object> review = (Map<String, Object>) finding.get("review");
        assertEquals(true, review.get("required"));
        assertEquals("pending", review.get("status"));
    }

    private Map<String, Object> envelope() throws Exception {
        String json = """
            {
              "schemaVersion": "pfi.disc-degenerative-findings.v1",
              "findings": [{
                "findingId": "finding-1",
                "findingType": "disc_bulging",
                "anatomy": {"level": "L4-L5", "side": null},
                "classification": {
                  "kind": "binary",
                  "label": "present",
                  "probabilities": {"absent": 0.2, "present": 0.8}
                },
                "evidence": {
                  "deploymentStatus": "supported_internal",
                  "evaluationDataset": "SPIDER_internal_test",
                  "externalValidationAvailable": false
                },
                "evaluation": {"status": "evaluated"},
                "sourceSeries": [{"role": "sagittal_t2", "available": true, "positions": [3,4,5]}],
                "localization": {
                  "source": "segmentation_derived_disc_level",
                  "researchOnly": true,
                  "automaticAnatomicalLocalizationValidated": false
                },
                "model": {
                  "modelId": "spider_degenerative_multitask_sagittal_t1_t2_2p5d",
                  "modelSha256": "16eccff327e6794b127fe372ecd03ea619a0f69d939b84ae1aa2e904191c6293"
                },
                "review": {"required": true, "status": "pending"},
                "notClinicalDiagnosis": true
              }]
            }
            """;
        return mapper.readValue(json, new TypeReference<Map<String, Object>>() {});
    }
}
