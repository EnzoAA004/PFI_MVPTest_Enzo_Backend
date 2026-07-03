package ar.edu.uade.pfi.backend.controller;

import ar.edu.uade.pfi.backend.client.AiServiceClient;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/ai/models")
public class AiModelRuntimeController {
    private final AiServiceClient aiServiceClient;

    public AiModelRuntimeController(AiServiceClient aiServiceClient) {
        this.aiServiceClient = aiServiceClient;
    }

    @GetMapping("/runtime")
    public Map<String, Object> runtime() {
        try {
            Map<String, Object> response = new LinkedHashMap<>(aiServiceClient.getModelRuntime());
            response.putIfAbsent("proxiedByBackend", true);
            response.putIfAbsent("humanReviewRequired", true);
            response.putIfAbsent("notClinicalDiagnosis", true);
            return response;
        } catch (RuntimeException ex) {
            return Map.of(
                "status", "pytorch_runtime_unavailable",
                "message", ex.getMessage() == null ? "AI Module unavailable" : ex.getMessage(),
                "proxiedByBackend", true,
                "humanReviewRequired", true,
                "notClinicalDiagnosis", true
            );
        }
    }
}
