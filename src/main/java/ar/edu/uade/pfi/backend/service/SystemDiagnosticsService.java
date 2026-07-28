package ar.edu.uade.pfi.backend.service;

import ar.edu.uade.pfi.backend.auth.AuthService;
import ar.edu.uade.pfi.backend.client.AiServiceOperations;
import ar.edu.uade.pfi.backend.config.AiMultiplanarContractVersion;
import ar.edu.uade.pfi.backend.config.AiServiceProperties;
import ar.edu.uade.pfi.backend.util.AiReportEvidence;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Service;

@Service
public class SystemDiagnosticsService {
    private final AiServiceOperations aiServiceClient;
    private final PostgresReviewStoreService postgresReviewStoreService;
    private final AuthService authService;
    private final RunAssetContentStorage assetContentStorage;
    private final boolean authEnabled;
    private final String persistenceMode;
    private final AiMultiplanarContractVersion multiplanarContractVersion;
    private final boolean adminBootstrapEnabled;
    private final OperationalMetricsService metrics;

    SystemDiagnosticsService(
        AiServiceOperations aiServiceClient,
        PostgresReviewStoreService postgresReviewStoreService,
        AuthService authService,
        boolean authEnabled,
        String persistenceMode
    ) {
        this(aiServiceClient, postgresReviewStoreService, authService, authEnabled, persistenceMode, AiMultiplanarContractVersion.V1);
    }

    SystemDiagnosticsService(
        AiServiceOperations aiServiceClient,
        PostgresReviewStoreService postgresReviewStoreService,
        AuthService authService,
        boolean authEnabled,
        String persistenceMode,
        AiMultiplanarContractVersion multiplanarContractVersion
    ) {
        this.aiServiceClient = aiServiceClient;
        this.postgresReviewStoreService = postgresReviewStoreService;
        this.authService = authService;
        this.assetContentStorage = null;
        this.authEnabled = authEnabled;
        this.persistenceMode = persistenceMode;
        this.multiplanarContractVersion = multiplanarContractVersion;
        this.adminBootstrapEnabled = false;
        this.metrics = null;
    }

    @Autowired
    public SystemDiagnosticsService(
        AiServiceOperations aiServiceClient,
        PostgresReviewStoreService postgresReviewStoreService,
        AuthService authService,
        ObjectProvider<RunAssetContentStorage> assetContentStorageProvider,
        AiServiceProperties aiServiceProperties,
        @Value("${pfi.auth.enabled:true}") boolean authEnabled,
        @Value("${pfi.persistence.mode:memory}") String persistenceMode,
        @Value("${pfi.auth.bootstrap-admin-enabled:false}") boolean adminBootstrapEnabled,
        @Nullable OperationalMetricsService metrics
    ) {
        this.aiServiceClient = aiServiceClient;
        this.postgresReviewStoreService = postgresReviewStoreService;
        this.authService = authService;
        this.assetContentStorage = assetContentStorageProvider.getIfAvailable();
        this.authEnabled = authEnabled;
        this.persistenceMode = persistenceMode;
        this.multiplanarContractVersion = aiServiceProperties.resolvedMultiplanarContractVersion();
        this.adminBootstrapEnabled = adminBootstrapEnabled;
        this.metrics = metrics;
    }

    public Map<String, Object> diagnostics() {
        Map<String, Object> aiModule = checkAiModule();
        Map<String, Object> database = postgresReviewStoreService.diagnostics();
        boolean aiOk = Boolean.TRUE.equals(aiModule.get("available"));
        boolean dbOk = Boolean.TRUE.equals(database.get("available"));
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("status", aiOk && dbOk ? "ok" : "degraded");
        result.put("checkedAt", Instant.now().toString());
        result.put("backend", Map.of("available", true, "status", "ok", "service", "pfi-backend"));
        result.put("aiModule", aiModule);
        result.put("database", database);
        result.put("assetStorage", assetStorageDiagnostics());
        result.put("auth", authEnabled ? authService.diagnostics() : Map.of("enabled", false, "status", "disabled"));
        result.put("persistence", Map.of("mode", persistenceMode, "postgresEnabled", postgresReviewStoreService.enabled()));
        result.put("readiness", aiModule.getOrDefault("readiness", readinessUnavailable("not_checked")));
        result.put("contract", aiModule.getOrDefault("contract", Map.of("status", "unavailable")));
        result.put("modelArtifacts", aiModule.getOrDefault("artifactSummary", Map.of("status", "unavailable")));
        result.put("evaluationEvidence", aiModule.getOrDefault("evaluationEvidence", evidenceUnavailable("not_checked")));
        result.put("adminAccountConfigured", authService.hasConfiguredAdminSafe());
        result.put("adminBootstrapEnabled", adminBootstrapEnabled);
        result.put("observability", observability());
        result.put("humanReviewRequired", true);
        result.put("notClinicalDiagnosis", true);
        return result;
    }

    /**
     * In-memory, fixed-shape counters only — see docs/P10_B_ERRORS_AUDIT_OBSERVABILITY.md
     * for the honest limitations (resets on restart, not Cloud Monitoring). Never
     * includes emails, actorIds, caseIds, runIds, individual traceIds, URLs, secrets,
     * paths, file names, or stack traces — every field here is a fixed counter/average.
     */
    private Map<String, Object> observability() {
        if (metrics == null) {
            return Map.of("status", "unavailable");
        }
        return metrics.snapshot();
    }

    private Map<String, Object> assetStorageDiagnostics() {
        if (assetContentStorage == null) {
            return Map.of(
                "mode", "none",
                "available", false,
                "storedAssetCount", 0,
                "storedBytes", 0,
                "missingPayloadCount", 0
            );
        }
        var diagnostics = assetContentStorage.diagnostics();
        return Map.of(
            "mode", diagnostics.mode(),
            "available", diagnostics.available(),
            "storedAssetCount", diagnostics.storedAssetCount(),
            "storedBytes", diagnostics.storedBytes(),
            "missingPayloadCount", diagnostics.missingPayloadCount()
        );
    }

    public Map<String, Object> warmup() {
        try {
            Map<String, Object> response = aiServiceClient.warmup();
            Map<String, Object> readiness = safeReadiness();
            Map<String, Object> evidence = safeEvidenceSummary();
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("status", "ok");
            result.put("checkedAt", Instant.now().toString());
            result.put("aiModule", response);
            result.put("readiness", readiness);
            result.put("evaluationEvidence", evidence);
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
                "readiness", readinessUnavailable(compact(ex.getMessage())),
                "evaluationEvidence", evidenceUnavailable(compact(ex.getMessage())),
                "contract", Map.of("status", "unavailable"),
                "modelArtifacts", Map.of("status", "unavailable"),
                "message", "AI Module warmup failed"
            );
        }
    }

    private Map<String, Object> checkAiModule() {
        try {
            Map<String, Object> response = aiServiceClient.health();
            Map<String, Object> readiness = safeReadiness();
            Map<String, Object> evidence = safeEvidenceSummary();
            Object models = safeModels();
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("available", true);
            result.put("status", response.getOrDefault("status", "ok"));
            result.put("service", "pfi-ai-module");
            result.put("defaultInferenceMode", response.get("defaultInferenceMode"));
            result.put("readiness", readiness);
            result.put("evaluationEvidence", evidence);
            result.put("artifactSummary", response.get("artifactSummary"));
            result.put("contract", response.getOrDefault("contract", Map.of("status", "unavailable")));
            result.put("models", models);
            result.put("response", response);
            result.putAll(multiplanarContractDiagnostics());
            return result;
        } catch (RuntimeException ex) {
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("available", false);
            result.put("status", "degraded");
            result.put("service", "pfi-ai-module");
            result.put("readiness", readinessUnavailable(compact(ex.getMessage())));
            result.put("evaluationEvidence", evidenceUnavailable(compact(ex.getMessage())));
            result.put("contract", Map.of("status", "unavailable"));
            result.put("artifactSummary", Map.of("status", "unavailable"));
            result.put("message", compact(ex.getMessage()));
            result.putAll(multiplanarContractDiagnostics());
            return result;
        }
    }

    /**
     * Never includes the AI Module base URL (may be sensitive infra info) — only the
     * contract version, the endpoint path it resolves to, and the schemaVersion the
     * backend expects back, which is all that's needed to verify a v1/v2 rollout.
     */
    private Map<String, Object> multiplanarContractDiagnostics() {
        boolean isV2 = multiplanarContractVersion == AiMultiplanarContractVersion.V2;
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("multiplanarContractVersion", isV2 ? "v2" : "v1");
        result.put("multiplanarEndpoint", isV2 ? "/v2/multiplanar/run" : "/multiplanar/run");
        result.put("multiplanarSchemaExpected", isV2 ? "pfi.multiplanar-run.v2" : "multiplanar-run-v1");
        return result;
    }

    private Map<String, Object> safeReadiness() {
        try {
            return aiServiceClient.readiness();
        } catch (RuntimeException ex) {
            return readinessUnavailable(compact(ex.getMessage()));
        }
    }

    private Map<String, Object> safeEvidenceSummary() {
        try {
            Map<String, Object> evidence = new LinkedHashMap<>(aiServiceClient.getEvaluationEvidence());
            evidence.putIfAbsent("humanReviewRequired", true);
            evidence.putIfAbsent("notClinicalDiagnosis", true);
            return evidence;
        } catch (RuntimeException ex) {
            return localEvidenceFallback(compact(ex.getMessage()));
        }
    }

    private Map<String, Object> localEvidenceFallback(String message) {
        try {
            Map<String, Object> reports = aiServiceClient.getRecentAgentReports(20);
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("status", "evaluation_evidence_fallback");
            result.put("reportCount", reports.getOrDefault("count", 0));
            result.put("hasReports", AiReportEvidence.hasReports(reports));
            result.put("latestRunId", AiReportEvidence.latestRunId(reports));
            result.put("message", message);
            result.put("humanReviewRequired", true);
            result.put("notClinicalDiagnosis", true);
            return result;
        } catch (RuntimeException ignored) {
            return evidenceUnavailable(message);
        }
    }

    private Map<String, Object> readinessUnavailable(String message) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("status", "unavailable");
        result.put("readyForDemo", false);
        result.put("readyForRealInference", false);
        result.put("recommendedInferenceMode", "contract");
        result.put("message", message);
        result.put("humanReviewRequired", true);
        result.put("notClinicalDiagnosis", true);
        return result;
    }

    private Map<String, Object> evidenceUnavailable(String message) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("status", "unavailable");
        result.put("reportCount", 0);
        result.put("hasReports", false);
        result.put("latestRunId", "");
        result.put("message", message);
        result.put("humanReviewRequired", true);
        result.put("notClinicalDiagnosis", true);
        return result;
    }

    private Object safeModels() {
        try {
            return aiServiceClient.models();
        } catch (RuntimeException ex) {
            return Map.of("status", "degraded", "message", compact(ex.getMessage()));
        }
    }

    private String compact(String value) {
        if (value == null || value.isBlank()) return "unavailable";
        String oneLine = value.replace('\n', ' ').replace('\r', ' ').replace('\t', ' ').trim();
        return oneLine.length() > 180 ? oneLine.substring(0, 180) + "..." : oneLine;
    }
}
