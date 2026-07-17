package ar.edu.uade.pfi.backend.controller;

import ar.edu.uade.pfi.backend.auth.RoleAuthorizationService;
import ar.edu.uade.pfi.backend.client.AiServiceOperations;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Map;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/ai/models")
public class AiModelSyncController {
    private final AiServiceOperations aiServiceClient;
    private final RoleAuthorizationService authorizationService;

    public AiModelSyncController(AiServiceOperations aiServiceClient) {
        this(aiServiceClient, null);
    }

    @Autowired
    public AiModelSyncController(AiServiceOperations aiServiceClient, RoleAuthorizationService authorizationService) {
        this.aiServiceClient = aiServiceClient;
        this.authorizationService = authorizationService;
    }

    @PostMapping("/sync")
    public Map<String, Object> sync(@RequestParam(defaultValue = "false") boolean force, HttpServletRequest request) {
        if (authorizationService != null) {
            authorizationService.requireAdmin(request, "models.sync");
        }
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
