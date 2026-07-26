package ar.edu.uade.pfi.backend.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import ar.edu.uade.pfi.backend.auth.AuthService;
import ar.edu.uade.pfi.backend.client.AiServiceOperations;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.lang.reflect.Constructor;
import java.lang.reflect.Proxy;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;

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
        assertEquals("evidence_summary_ready", evidence.get("status"));
        assertEquals(1, evidence.get("reportCount"));
        assertEquals("run-test", evidence.get("latestRunId"));

        @SuppressWarnings("unchecked")
        Map<String, Object> aiModule = (Map<String, Object>) diagnostics.get("aiModule");
        assertTrue(aiModule.containsKey("contract"));
        assertTrue(aiModule.containsKey("artifactSummary"));
        assertTrue(aiModule.containsKey("readiness"));
        assertTrue(aiModule.containsKey("evaluationEvidence"));
    }

    @Test
    void productionConstructorIsAutowiredAndNoDefaultConstructorIsRequired() {
        Constructor<?>[] constructors = SystemDiagnosticsService.class.getDeclaredConstructors();

        assertFalse(Arrays.stream(constructors).anyMatch(constructor -> constructor.getParameterCount() == 0));
        Constructor<?> autowired = Arrays.stream(constructors)
            .filter(constructor -> constructor.isAnnotationPresent(Autowired.class))
            .findFirst()
            .orElseThrow();

        assertEquals(6, autowired.getParameterCount());
        assertEquals(AiServiceOperations.class, autowired.getParameterTypes()[0]);
        assertEquals(PostgresReviewStoreService.class, autowired.getParameterTypes()[1]);
        assertEquals(AuthService.class, autowired.getParameterTypes()[2]);
        assertEquals(ObjectProvider.class, autowired.getParameterTypes()[3]);
    }

    @Test
    void applicationContextCreatesSystemDiagnosticsServiceWithProductionConstructor() {
        try (AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext()) {
            AuthService authService = mock(AuthService.class);
            when(authService.diagnostics()).thenReturn(Map.of("enabled", true, "status", "ok"));
            context.getEnvironment().getSystemProperties().put("pfi.auth.enabled", "false");
            context.getEnvironment().getSystemProperties().put("pfi.persistence.mode", "memory");
            context.registerBean(AiServiceOperations.class, () -> aiClient(Map.of("status", "ok")));
            context.registerBean(PostgresReviewStoreService.class, () -> new PostgresReviewStoreService(new ObjectMapper(), "memory", ""));
            context.registerBean(AuthService.class, () -> authService);
            context.registerBean(SystemDiagnosticsService.class);

            context.refresh();

            SystemDiagnosticsService service = context.getBean(SystemDiagnosticsService.class);
            assertNotNull(service);
            @SuppressWarnings("unchecked")
            Map<String, Object> assetStorage = (Map<String, Object>) service.diagnostics().get("assetStorage");
            assertEquals("none", assetStorage.get("mode"));
        }
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
                case "getEvaluationSummary" -> Map.of("status", "evaluation_evidence_available", "reportCount", 1, "latestRunId", "run-test");
                case "getEvaluationEvidence" -> Map.of("status", "evidence_summary_ready", "reportCount", 1, "latestRunId", "run-test", "humanReviewRequired", true, "notClinicalDiagnosis", true);
                default -> throw new UnsupportedOperationException(method.getName());
            }
        );
    }
}
