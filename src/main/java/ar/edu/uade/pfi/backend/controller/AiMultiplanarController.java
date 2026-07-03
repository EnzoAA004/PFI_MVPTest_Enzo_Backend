package ar.edu.uade.pfi.backend.controller;

import ar.edu.uade.pfi.backend.client.AiServiceOperations;
import java.util.List;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/ai/multiplanar")
public class AiMultiplanarController {
    private final AiServiceOperations aiServiceClient;

    public AiMultiplanarController(AiServiceOperations aiServiceClient) {
        this.aiServiceClient = aiServiceClient;
    }

    @GetMapping("/contract")
    public Map<String, Object> contract() {
        try {
            Map<String, Object> response = aiServiceClient.getMultiplanarContract();
            response.putIfAbsent("proxiedByBackend", true);
            response.putIfAbsent("humanReviewRequired", true);
            response.putIfAbsent("notClinicalDiagnosis", true);
            return response;
        } catch (RuntimeException ex) {
            return fallback(ex.getMessage());
        }
    }

    private Map<String, Object> fallback(String message) {
        return Map.of(
            "status", "multiplanar_unavailable",
            "schemaVersion", "multiplanar-workspace-v1",
            "workspaceMode", "dual_plane_with_3d_context",
            "panels", List.of("sagittal", "axial", "three_d"),
            "message", message == null ? "AI Module unavailable" : message,
            "proxiedByBackend", true,
            "degradedMode", true,
            "humanReviewRequired", true,
            "notClinicalDiagnosis", true
        );
    }
}
