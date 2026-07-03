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
            "metrics", List.of("dice", "iou", "hausdorff95", "measurement_error", "review_agreement"),
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
            "reports", reports,
            "readiness", safeReadiness(),
            "humanReviewRequired", true,
            "notClinicalDiagnosis", true
        );
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
