package ar.edu.uade.pfi.backend.controller;

import ar.edu.uade.pfi.backend.auth.RoleAuthorizationService;
import ar.edu.uade.pfi.backend.client.AiServiceClient;
import jakarta.servlet.http.HttpServletRequest;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * P10-B.2: internal model runtime configuration (pytorch device/version, artifact
 * state) has no documented professional consumer — same rationale as
 * /api/ai/readiness and /api/ai/models/verify (P10-A.1) — so it is ADMIN-only.
 * Previously this endpoint had no authorization check at all beyond AuthFilter's
 * blanket "any authenticated professional" gate.
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
            // Never ex.getMessage() — could carry an AI Module host/path/stack detail.
            return Map.of(
                "status", "pytorch_runtime_unavailable",
                "message", "AI Module no disponible.",
                "proxiedByBackend", true,
                "humanReviewRequired", true,
                "notClinicalDiagnosis", true
            );
        }
    }
}
