package ar.edu.uade.pfi.backend.controller;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ar.edu.uade.pfi.backend.config.AiServiceProperties;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

class AiContractControllerTest {

    @Test
    void pipelineSchemaProxiesAiModuleAndForcesGovernanceFlags() {
        WebClient webClient = WebClient.builder()
            .exchangeFunction(request -> Mono.just(ClientResponse.create(HttpStatus.OK)
                .header("Content-Type", "application/json")
                .body("""
                    {
                      "schema_version": "visual-review-contract-v1",
                      "status": "stable",
                      "human_review_required": false,
                      "not_clinical_diagnosis": false,
                      "root_fields": {"series": "viewer series"}
                    }
                    """)
                .build()))
            .build();
        AiContractController controller = new AiContractController(webClient, new AiServiceProperties("http://ai-module", 1));

        Map<String, Object> response = controller.pipelineSchema();

        assertEquals("visual-review-contract-v1", response.get("schemaVersion"));
        assertEquals("stable", response.get("status"));
        assertEquals(true, response.get("proxiedByBackend"));
        assertEquals(true, response.get("aiModuleAvailable"));
        assertEquals(true, response.get("humanReviewRequired"));
        assertEquals(true, response.get("notClinicalDiagnosis"));
        assertFalse(response.containsKey("schema_version"));
        assertTrue(response.containsKey("rootFields"));
    }

    @Test
    void pipelineSchemaReturnsDegradedFallbackWhenAiModuleIsUnavailable() {
        WebClient webClient = WebClient.builder()
            .exchangeFunction(request -> Mono.error(new IllegalStateException("connection refused")))
            .build();
        AiContractController controller = new AiContractController(webClient, new AiServiceProperties("http://ai-module", 1));

        Map<String, Object> response = controller.pipelineSchema();

        assertEquals("visual-review-contract-v1", response.get("schemaVersion"));
        assertEquals("degraded_fallback", response.get("status"));
        assertEquals("pfi-backend.ai-contract-fallback", response.get("generatedBy"));
        assertEquals("backend-fallback-visual-review-contract-v1", response.get("schemaHash"));
        assertEquals(true, response.get("proxiedByBackend"));
        assertEquals(false, response.get("aiModuleAvailable"));
        assertEquals(true, response.get("degradedMode"));
        assertEquals(true, response.get("humanReviewRequired"));
        assertEquals(true, response.get("notClinicalDiagnosis"));
        assertTrue(String.valueOf(response.get("message")).contains("AI Module is not available"));
        assertTrue(response.containsKey("rootFields"));
        assertTrue(response.containsKey("guarantees"));
    }

    @Test
    void pipelineSchemaVerificationProxiesAiModuleAndForcesGovernanceFlags() {
        WebClient webClient = WebClient.builder()
            .exchangeFunction(request -> Mono.just(ClientResponse.create(HttpStatus.OK)
                .header("Content-Type", "application/json")
                .body("""
                    {
                      "schema_version": "visual-review-contract-v1",
                      "schema_hash": "abc123",
                      "recomputed_hash": "abc123",
                      "hash_valid": true,
                      "governance_valid": true,
                      "valid": true,
                      "human_review_required": false,
                      "not_clinical_diagnosis": false
                    }
                    """)
                .build()))
            .build();
        AiContractController controller = new AiContractController(webClient, new AiServiceProperties("http://ai-module", 1));

        Map<String, Object> response = controller.pipelineSchemaVerification();

        assertEquals("visual-review-contract-v1", response.get("schemaVersion"));
        assertEquals("abc123", response.get("schemaHash"));
        assertEquals("abc123", response.get("recomputedHash"));
        assertEquals(true, response.get("hashValid"));
        assertEquals(true, response.get("governanceValid"));
        assertEquals(true, response.get("valid"));
        assertEquals(true, response.get("proxiedByBackend"));
        assertEquals(true, response.get("aiModuleAvailable"));
        assertEquals(true, response.get("humanReviewRequired"));
        assertEquals(true, response.get("notClinicalDiagnosis"));
        assertFalse(response.containsKey("schema_hash"));
    }

    @Test
    void pipelineSchemaVerificationReturnsDegradedFallbackWhenAiModuleIsUnavailable() {
        WebClient webClient = WebClient.builder()
            .exchangeFunction(request -> Mono.error(new IllegalStateException("connection refused")))
            .build();
        AiContractController controller = new AiContractController(webClient, new AiServiceProperties("http://ai-module", 1));

        Map<String, Object> response = controller.pipelineSchemaVerification();

        assertEquals("visual-review-contract-v1", response.get("schemaVersion"));
        assertEquals("backend-fallback-visual-review-contract-v1", response.get("schemaHash"));
        assertEquals("unavailable", response.get("recomputedHash"));
        assertEquals(false, response.get("hashValid"));
        assertEquals(true, response.get("governanceValid"));
        assertEquals(false, response.get("valid"));
        assertEquals(false, response.get("aiModuleAvailable"));
        assertEquals(true, response.get("degradedMode"));
        assertTrue(String.valueOf(response.get("message")).contains("not available"));
    }
}
