package ar.edu.uade.pfi.backend.service;

import ar.edu.uade.pfi.backend.client.AiServiceClient;
import ar.edu.uade.pfi.backend.dto.PipelineRunRequestDto;
import ar.edu.uade.pfi.backend.dto.ReviewStatusDto;
import ar.edu.uade.pfi.backend.dto.ReviewUpdateRequestDto;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.stereotype.Service;

@Service
public class AiBackendService {
    private final AiServiceClient aiServiceClient;
    private final ReviewStoreService reviewStoreService;

    public AiBackendService(AiServiceClient aiServiceClient, ReviewStoreService reviewStoreService) {
        this.aiServiceClient = aiServiceClient;
        this.reviewStoreService = reviewStoreService;
    }

    public Map<String, Object> health() {
        try {
            return withHumanReview(aiServiceClient.health());
        } catch (RuntimeException ex) {
            return Map.of(
                "status", "down",
                "aiModuleAvailable", false,
                "humanReviewRequired", true,
                "message", ex.getMessage()
            );
        }
    }

    public Object models() {
        return aiServiceClient.models();
    }

    public Map<String, Object> runPipeline(PipelineRunRequestDto request) {
        Map<String, Object> response = withHumanReview(aiServiceClient.runPipeline(request));
        String runId = extractRunId(response);
        if (runId != null) {
            response.put("review", reviewStoreService.findOrDefault(runId));
        }
        return response;
    }

    public Map<String, Object> getAgentReport(String runId) {
        Map<String, Object> response = withHumanReview(aiServiceClient.getAgentReport(runId));
        response.put("review", reviewStoreService.findOrDefault(runId));
        return response;
    }

    public ReviewStatusDto updateReview(String runId, ReviewUpdateRequestDto request) {
        return reviewStoreService.updateReview(runId, request);
    }

    private Map<String, Object> withHumanReview(Map<String, Object> response) {
        Map<String, Object> normalized = response == null ? new LinkedHashMap<>() : new LinkedHashMap<>(response);
        normalized.put("humanReviewRequired", true);
        return normalized;
    }

    private String extractRunId(Map<String, Object> response) {
        Object runId = response.get("runId");
        if (runId == null) {
            runId = response.get("run_id");
        }
        return runId == null ? null : runId.toString();
    }
}
