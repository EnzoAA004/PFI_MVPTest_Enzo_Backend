package ar.edu.uade.pfi.backend.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ar.edu.uade.pfi.backend.client.AiServiceOperations;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.lang.reflect.Proxy;
import java.util.Map;
import org.junit.jupiter.api.Test;

class SystemDiagnosticsServiceTest {

    @Test
    void diagnosticsExposeAiContractFingerprintFromHealth() {
        AiServiceOperations aiClient = aiClient(Map.of(
            "status", "ok",
            "defaultInferenceMode", "contract",
            "artifactSummary", Map.of("readyForRealInference", false),
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
        Map<String, Object> aiModule = (Map<String, Object>) diagnostics.get("aiModule");
        assertTrue(aiModule.containsKey("contract"));
    }

    private AiServiceOperations aiClient(Map<String, Object> healthResponse) {
        return (AiServiceOperations) Proxy.newProxyInstance(
            AiServiceOperations.class.getClassLoader(),
            new Class<?>[] {AiServiceOperations.class},
            (proxy, method, args) -> switch (method.getName()) {
                case "health" -> healthResponse;
                case "models" -> Map.of("status", "ok");
                case "warmup" -> healthResponse;
                case "pipelineSchema" -> Map.of("status", "stable");
                case "runPipeline" -> Map.of("runId", "run-test");
                case "getAgentReport" -> Map.of("runId", args == null ? "run-test" : String.valueOf(args[0]));
                default -> throw new UnsupportedOperationException(method.getName());
            }
        );
    }
}
