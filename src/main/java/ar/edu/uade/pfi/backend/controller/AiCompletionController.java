package ar.edu.uade.pfi.backend.controller;

import ar.edu.uade.pfi.backend.client.AiServiceOperations;
import java.util.List;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/ai")
public class AiCompletionController {
    private final AiServiceOperations aiServiceClient;

    public AiCompletionController(AiServiceOperations aiServiceClient) {
        this.aiServiceClient = aiServiceClient;
    }

    @GetMapping("/completion")
    public Map<String, Object> completion() {
        Map<String, Object> readiness = safeReadiness();
        Map<String, Object> reports = safeReports();
        boolean readyForDemo = Boolean.TRUE.equals(readiness.get("readyForDemo"));
        boolean hasReports = intValue(reports.get("count")) > 0;
        int complete = 4 + (readyForDemo ? 1 : 0) + (hasReports ? 1 : 0);
        return Map.of(
            "status", "mvp_completion_ready",
            "completionPercent", Math.round(complete * 100.0 / 6.0),
            "items", List.of("backend", "ai_module", "traceability", "human_review", "readiness", "reports"),
            "aiMvpCompletion", readiness.getOrDefault("mvpCompletion", Map.of()),
            "roadmap", roadmapSummary(),
            "readiness", readiness,
            "reports", reports,
            "humanReviewRequired", true,
            "notClinicalDiagnosis", true
        );
    }

    private Map<String, Object> roadmapSummary() {
        return Map.of(
            "currentMode", "contract",
            "completed", List.of("traceability", "readiness", "artifact_verification", "report_index", "human_review"),
            "pending", List.of("real_model_artifact", "quantitative_dataset_evaluation", "professional_validation_round"),
            "acceptanceCriteria", List.of("contract_schema_valid", "professional_review_required", "not_clinical_diagnosis", "demo_ready", "trace_id_available")
        );
    }

    private Map<String, Object> safeReadiness() {
        try {
            return aiServiceClient.readiness();
        } catch (RuntimeException ex) {
            return Map.of("status", "unavailable", "readyForDemo", false);
        }
    }

    private Map<String, Object> safeReports() {
        try {
            return aiServiceClient.getRecentAgentReports(20);
        } catch (RuntimeException ex) {
            return Map.of("status", "unavailable", "count", 0);
        }
    }

    private int intValue(Object value) {
        try {
            return Integer.parseInt(String.valueOf(value));
        } catch (RuntimeException ex) {
            return 0;
        }
    }
}
