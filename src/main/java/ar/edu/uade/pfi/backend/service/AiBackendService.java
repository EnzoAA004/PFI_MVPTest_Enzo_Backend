package ar.edu.uade.pfi.backend.service;

import ar.edu.uade.pfi.backend.client.AiServiceOperations;
import ar.edu.uade.pfi.backend.dto.PipelineRunRequestDto;
import ar.edu.uade.pfi.backend.dto.ReviewStatusDto;
import ar.edu.uade.pfi.backend.dto.ReviewUpdateRequestDto;
import ar.edu.uade.pfi.backend.util.ResponseNormalizer;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;

@Service
public class AiBackendService {
    private final AiServiceOperations aiServiceClient;
    private final ReviewStoreService reviewStoreService;

    public AiBackendService(AiServiceOperations aiServiceClient, ReviewStoreService reviewStoreService) {
        this.aiServiceClient = aiServiceClient;
        this.reviewStoreService = reviewStoreService;
    }

    public Map<String, Object> health() {
        try {
            Map<String, Object> response = normalizeForFrontend(aiServiceClient.health());
            response.put("backendStatus", "up");
            response.put("aiModuleAvailable", true);
            return response;
        } catch (RuntimeException ex) {
            return Map.of(
                "backendStatus", "up",
                "aiModuleAvailable", false,
                "humanReviewRequired", true,
                "notClinicalDiagnosis", true,
                "message", ex.getMessage()
            );
        }
    }

    public Object models() {
        return ResponseNormalizer.normalizeObject(aiServiceClient.models());
    }

    public Map<String, Object> runPipeline(PipelineRunRequestDto request) {
        Map<String, Object> response = normalizeForFrontend(aiServiceClient.runPipeline(request));
        String runId = extractRunId(response);
        if (runId != null) {
            response.put("review", reviewStoreService.findOrDefault(runId));
        }
        return response;
    }

    public Map<String, Object> getAgentReport(String runId) {
        try {
            Map<String, Object> response = normalizeForFrontend(aiServiceClient.getAgentReport(runId));
            response.put("review", reviewStoreService.findOrDefault(runId));
            return response;
        } catch (RuntimeException ex) {
            return normalizeForFrontend(Map.of(
                "runId", runId,
                "caseId", "unknown",
                "status", "agent_report_unavailable",
                "agentDecision", Map.of(
                    "priority", "media",
                    "status", "requiere_revision",
                    "flags", List.of("agent_report_unavailable", "revision_profesional_requerida"),
                    "reasons", List.of("El AI Module no devolvio un reporte persistido para este runId; se conserva revision profesional obligatoria."),
                    "humanReviewRequired", true
                ),
                "measurements", List.of(),
                "review", reviewStoreService.findOrDefault(runId),
                "message", ex.getMessage()
            ));
        }
    }

    public ReviewStatusDto updateReview(String runId, ReviewUpdateRequestDto request) {
        return reviewStoreService.updateReview(runId, request);
    }

    private Map<String, Object> normalizeForFrontend(Map<String, Object> response) {
        Map<String, Object> normalized = ResponseNormalizer.normalizeMap(response);
        normalized.put("humanReviewRequired", true);
        normalized.put("notClinicalDiagnosis", true);
        return normalized;
    }

    private String extractRunId(Map<String, Object> response) {
        Object runId = response.get("runId");
        return runId == null ? null : runId.toString();
    }
}
