package ar.edu.uade.pfi.backend.controller;

import ar.edu.uade.pfi.backend.auth.RoleAuthorizationService;
import ar.edu.uade.pfi.backend.client.AiServiceClient;
import jakarta.servlet.http.HttpServletRequest;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * Internal model runtime configuration (pytorch device/version, artifact state) is
 * ADMIN-only for the same reason as /api/ai/readiness and /api/ai/models/verify.
 */
@RestController
@RequestMapping("/api/ai/models")
public class AiModelRuntimeController {
    private final AiServiceClient aiServiceClient;
    private final RoleAuthorizationService authorizationService;

    public AiModelRuntimeController(AiServiceClient aiServiceClient, RoleAuthorizationService authorizationService) {
        this.aiServiceClient = aiServiceClient;
        this.authorizationService = authorizationService;
    }

    @GetMapping("/runtime")
    public Map<String, Object> runtime(HttpServletRequest request) {
        authorizationService.requireAdmin(request, "ai.models.runtime");
        try {
            Map<String, Object> response = new LinkedHashMap<>(aiServiceClient.getModelRuntime());
            response.putIfAbsent("proxiedByBackend", true);
            response.putIfAbsent("humanReviewRequired", true);
            response.putIfAbsent("notClinicalDiagnosis", true);
            return response;
        } catch (RuntimeException ex) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "AI Module no disponible.");
        }
    }
}
