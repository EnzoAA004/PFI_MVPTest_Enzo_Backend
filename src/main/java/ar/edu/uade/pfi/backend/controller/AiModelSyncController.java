package ar.edu.uade.pfi.backend.controller;

import ar.edu.uade.pfi.backend.client.AiServiceOperations;
import java.util.Map;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/ai/models")
public class AiModelSyncController {
    private final AiServiceOperations aiServiceClient;

    public AiModelSyncController(AiServiceOperations aiServiceClient) {
        this.aiServiceClient = aiServiceClient;
    }

    @PostMapping("/sync")
    public Map<String, Object> sync(@RequestParam(defaultValue = "false") boolean force) {
        try {
            Map<String, Object> response = aiServiceClient.syncModels(force);
            response.putIfAbsent("proxiedByBackend", true);
            response.putIfAbsent("humanReviewRequired", true);
            response.putIfAbsent("notClinicalDiagnosis", true);
            return response;
        } catch (RuntimeException ex) {
            return Map.of(
                "status", "models_sync_failed",
                "message", ex.getMessage() == null ? "AI Module unavailable" : ex.getMessage(),
                "force", force,
                "proxiedByBackend", true,
                "humanReviewRequired", true,
                "notClinicalDiagnosis", true
            );
        }
    }
}
