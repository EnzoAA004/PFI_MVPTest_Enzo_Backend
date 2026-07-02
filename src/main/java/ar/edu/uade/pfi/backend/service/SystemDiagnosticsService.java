package ar.edu.uade.pfi.backend.service;

import ar.edu.uade.pfi.backend.auth.AuthService;
import ar.edu.uade.pfi.backend.client.AiServiceOperations;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

@Service
public class SystemDiagnosticsService {
    private final AiServiceOperations aiServiceClient;
    private final PostgresReviewStoreService postgresReviewStoreService;
    private final AuthService authService;
    private final boolean authEnabled;
    private final String persistenceMode;

    public SystemDiagnosticsService(
        AiServiceOperations aiServiceClient,
        PostgresReviewStoreService postgresReviewStoreService,
        AuthService authService,
        @Value("${pfi.auth.enabled:true}") boolean authEnabled,
        @Value("${pfi.persistence.mode:memory}") String persistenceMode
    ) {
        this.aiServiceClient = aiServiceClient;
        this.postgresReviewStoreService = postgresReviewStoreService;
        this.authService = authService;
        this.authEnabled = authEnabled;
        this.persistenceMode = persistenceMode;
    }

    public Map<String, Object> diagnostics() {
        Map<String, Object> aiModule = checkAiModule();
        Map<String, Object> database = postgresReviewStoreService.diagnostics();
        boolean aiOk = Boolean.TRUE.equals(aiModule.get("available"));
        boolean dbOk = Boolean.TRUE.equals(database.get("available"));
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("status", aiOk && dbOk ? "ok" : "degraded");
        result.put("checkedAt", Instant.now().toString());
        result.put("backend", Map.of(
            "available", true,
            "status", "ok",
            "service", "pfi-backend"
        ));
        result.put("aiModule", aiModule);
        result.put("database", database);
        result.put("auth", authEnabled ? authService.diagnostics() : Map.of("enabled", false, "status", "disabled"));
        result.put("persistence", Map.of(
            "mode", persistenceMode,
            "postgresEnabled", postgresReviewStoreService.enabled()
        ));
        result.put("contract", aiModule.getOrDefault("contract", Map.of("status", "unavailable")));
        result.put("modelArtifacts", aiModule.getOrDefault("artifactSummary", Map.of("status", "unavailable")));
        result.put("humanReviewRequired", true);
        result.put("notClinicalDiagnosis", true);
        return result;
    }

    public Map<String, Object> warmup() {
        try {
            Map<String, Object> response = aiServiceClient.warmup();
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("status", "ok");
            result.put("checkedAt", Instant.now().toString());
            result.put("aiModule", response);
            result.put("artifactSummary", response.get("artifactSummary"));
            result.put("modelArtifacts", response.getOrDefault("artifactSummary", Map.of("status", "unavailable")));
            result.put("contract", response.getOrDefault("contract", Map.of("status", "unavailable")));
            result.put("defaultInferenceMode", response.get("defaultInferenceMode"));
            result.put("message", "AI Module warmup completed");
            return result;
        } catch (RuntimeException ex) {
            return Map.of(
                "status", "degraded",
                "checkedAt", Instant.now().toString(),
                "aiModule", Map.of("available", false, "message", compact(ex.getMessage())),
                "contract", Map.of("status", "unavailable"),
                "modelArtifacts", Map.of("status", "unavailable"),
                "message", "AI Module warmup failed"
            );
        }
    }

    private Map<String, Object> checkAiModule() {
        try {
            Map<String, Object> response = aiServiceClient.health();
            Object models = safeModels();
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("available", true);
            result.put("status", response.getOrDefault("status", "ok"));
            result.put("service", "pfi-ai-module");
            result.put("defaultInferenceMode", response.get("defaultInferenceMode"));
            result.put("artifactSummary", response.get("artifactSummary"));
            result.put("contract", response.getOrDefault("contract", Map.of("status", "unavailable")));
            result.put("models", models);
            result.put("response", response);
            return result;
        } catch (RuntimeException ex) {
            return Map.of(
                "available", false,
                "status", "degraded",
                "service", "pfi-ai-module",
                "contract", Map.of("status", "unavailable"),
                "artifactSummary", Map.of("status", "unavailable"),
                "message", compact(ex.getMessage())
            );
        }
    }

    private Object safeModels() {
        try {
            return aiServiceClient.models();
        } catch (RuntimeException ex) {
            return Map.of(
                "status", "degraded",
                "message", compact(ex.getMessage())
            );
        }
    }

    private String compact(String value) {
        if (value == null || value.isBlank()) return "unavailable";
        String oneLine = value.replace('\n', ' ').replace('\r', ' ').replace('\t', ' ').trim();
        return oneLine.length() > 180 ? oneLine.substring(0, 180) + "..." : oneLine;
    }
}
