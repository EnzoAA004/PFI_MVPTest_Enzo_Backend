package ar.edu.uade.pfi.backend.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ar.edu.uade.pfi.backend.client.AiServiceOperations;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.lang.reflect.Proxy;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class SystemDiagnosticsServiceTest {

    @Test
    void diagnosticsExposeAiContractFingerprintArtifactSummaryReadinessAndEvidence() {
        AiServiceOperations aiClient = aiClient(Map.of(
            "status", "ok",
            "defaultInferenceMode", "contract",
            "artifactSummary", Map.of(
                "readyForRealInference", false,
                "artifactsAvailable", 1,
                "artifactsMissing", 1,
                "artifactsHashed", 1,
                "hashAlgorithm", "sha256"
            ),
            "contract", Map.of(
                "schemaVersion", "visual-review-contract-v1",
                "schemaHash", "abc123",
                "status", "stable",
                "generatedBy", "pfi-ai-module.contract_schema"
            )
        ));
        SystemDiagnosticsService service = new SystemDiagnosticsService(
            aiClient,
            new PostgresReviewStoreService(new ObjectMapper(), "memory", ""),
            null,
            false,
            "memory"
        );

        Map<String, Object> diagnostics = service.diagnostics();

        assertEquals("ok", diagnostics.get("status"));
        @SuppressWarnings("unchecked")
        Map<String, Object> contract = (Map<String, Object>) diagnostics.get("contract");
        assertEquals("visual-review-contract-v1", contract.get("schemaVersion"));
        assertEquals("abc123", contract.get("schemaHash"));
        assertEquals("stable", contract.get("status"));

        @SuppressWarnings("unchecked")
        Map<String, Object> modelArtifacts = (Map<String, Object>) diagnostics.get("modelArtifacts");
        assertEquals(1, modelArtifacts.get("artifactsAvailable"));
        assertEquals(1, modelArtifacts.get("artifactsMissing"));
        assertEquals(1, modelArtifacts.get("artifactsHashed"));
        assertEquals("sha256", modelArtifacts.get("hashAlgorithm"));

        @SuppressWarnings("unchecked")
        Map<String, Object> readiness = (Map<String, Object>) diagnostics.get("readiness");
        assertEquals("contract_ready", readiness.get("status"));
        assertEquals(true, readiness.get("readyForDemo"));
        assertEquals(false, readiness.get("readyForRealInference"));

        @SuppressWarnings("unchecked")
        Map<String, Object> evidence = (Map<String, Object>) diagnostics.get("evaluationEvidence");
        assertEquals("evaluation_evidence_ready", evidence.get("status"));
        assertEquals(1, evidence.get("reportCount"));
        assertEquals("run-test", evidence.get("latestRunId"));

        @SuppressWarnings("unchecked")
        Map<String, Object> aiModule = (Map<String, Object>) diagnostics.get("aiModule");
        assertTrue(aiModule.containsKey("contract"));
        assertTrue(aiModule.containsKey("artifactSummary"));
        assertTrue(aiModule.containsKey("readiness"));
        assertTrue(aiModule.containsKey("evaluationEvidence"));
    }

    private AiServiceOperations aiClient(Map<String, Object> healthResponse) {
        return (AiServiceOperations) Proxy.newProxyInstance(
            AiServiceOperations.class.getClassLoader(),
            new Class<?>[] {AiServiceOperations.class},
            (proxy, method, args) -> switch (method.getName()) {
                case "health" -> healthResponse;
                case "readiness" -> Map.of("status", "contract_ready", "readyForDemo", true, "readyForRealInference", false);
                case "models" -> Map.of("status", "ok");
                case "verifyModels" -> Map.of("status", "degraded_contract_mode", "valid", false);
                case "warmup" -> healthResponse;
                case "pipelineSchema" -> Map.of("status", "stable");
                case "runPipeline" -> Map.of("runId", "run-test");
                case "getAgentReport" -> Map.of("runId", args == null ? "run-test" : String.valueOf(args[0]));
                case "getAgentReportSummary" -> Map.of("runId", args == null ? "run-test" : String.valueOf(args[0]), "summaryOnly", true);
                case "getRecentAgentReports" -> Map.of("status", "ok", "count", 1, "items", List.of(Map.of("runId", "run-test")));
                default -> throw new UnsupportedOperationException(method.getName());
            }
        );
    }
}
