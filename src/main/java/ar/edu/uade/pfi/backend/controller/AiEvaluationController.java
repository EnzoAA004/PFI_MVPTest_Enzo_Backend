package ar.edu.uade.pfi.backend.controller;

import ar.edu.uade.pfi.backend.client.AiServiceOperations;
import java.util.List;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/ai/evaluation")
public class AiEvaluationController {
    private final AiServiceOperations aiServiceClient;

    public AiEvaluationController(AiServiceOperations aiServiceClient) {
        this.aiServiceClient = aiServiceClient;
    }

    @GetMapping("/contract")
    public Map<String, Object> contract() {
        return Map.of(
            "status", "evaluation_contract_ready",
            "schemaVersion", "evaluation-contract-v1",
            "metrics", List.of(
                metric("dice", "segmentation", "higher_is_better"),
                metric("iou", "segmentation", "higher_is_better"),
                metric("hausdorff95", "boundary", "lower_is_better"),
                metric("measurement_error", "measurement", "lower_is_better"),
                metric("review_agreement", "professional_review", "higher_is_better")
            ),
            "requiredEvidence", List.of("trace_id", "run_id", "model_artifact_hash", "pipeline_schema_hash", "professional_review"),
            "readiness", safeReadiness(),
            "humanReviewRequired", true,
            "notClinicalDiagnosis", true
        );
    }

    @GetMapping("/summary")
    public Map<String, Object> summary() {
        Map<String, Object> reports = safeReports();
        return Map.of(
            "status", "evaluation_summary_ready",
            "reportCount", reports.getOrDefault("count", 0),
            "hasReports", hasReports(reports),
            "latestRunId", latestRunId(reports),
            "reports", reports,
            "readiness", safeReadiness(),
            "humanReviewRequired", true,
            "notClinicalDiagnosis", true
        );
    }

    private Map<String, Object> metric(String key, String category, String direction) {
        return Map.of("key", key, "category", category, "direction", direction, "required", true);
    }

    private boolean hasReports(Map<String, Object> reports) {
        Object count = reports.get("count");
        try {
            return Integer.parseInt(String.valueOf(count)) > 0;
        } catch (RuntimeException ex) {
            return false;
        }
    }

    private Object latestRunId(Map<String, Object> reports) {
        Object items = reports.get("items");
        if (!(items instanceof List<?>)) return "";
        List<?> list = (List<?>) items;
        if (list.isEmpty()) return "";
        Object first = list.get(0);
        if (!(first instanceof Map<?, ?>)) return "";
        Object runId = ((Map<?, ?>) first).get("runId");
        return runId == null ? "" : runId;
    }

    private Map<String, Object> safeReadiness() {
        try {
            return aiServiceClient.readiness();
        } catch (RuntimeException ex) {
            return Map.of("status", "unavailable", "message", ex.getMessage());
        }
    }

    private Map<String, Object> safeReports() {
        try {
            return aiServiceClient.getRecentAgentReports(100);
        } catch (RuntimeException ex) {
            return Map.of("status", "unavailable", "count", 0, "items", List.of(), "message", ex.getMessage());
        }
    }
}
