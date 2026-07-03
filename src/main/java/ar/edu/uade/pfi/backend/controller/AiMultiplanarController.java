package ar.edu.uade.pfi.backend.controller;

import ar.edu.uade.pfi.backend.client.AiServiceOperations;
import ar.edu.uade.pfi.backend.dto.MultiplanarRunRequestDto;
import jakarta.validation.Valid;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
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
            Map<String, Object> response = new LinkedHashMap<>(aiServiceClient.getMultiplanarContract());
            response.putIfAbsent("proxiedByBackend", true);
            response.putIfAbsent("humanReviewRequired", true);
            response.putIfAbsent("notClinicalDiagnosis", true);
            return response;
        } catch (RuntimeException ex) {
            return fallback(ex.getMessage());
        }
    }

    @PostMapping("/run")
    public Map<String, Object> run(@Valid @RequestBody MultiplanarRunRequestDto request) {
        try {
            Map<String, Object> response = new LinkedHashMap<>(aiServiceClient.runMultiplanar(normalizedRequest(request)));
            response.putIfAbsent("proxiedByBackend", true);
            response.putIfAbsent("humanReviewRequired", true);
            response.putIfAbsent("notClinicalDiagnosis", true);
            return response;
        } catch (RuntimeException ex) {
            Map<String, Object> response = new LinkedHashMap<>(fallback(ex.getMessage()));
            response.put("status", "multiplanar_run_failed");
            response.put("caseId", request.caseId());
            return response;
        }
    }

    private MultiplanarRunRequestDto normalizedRequest(MultiplanarRunRequestDto request) {
        String caseId = request.caseId().trim();
        String sagittalPath = valueOrDefault(request.sagittalInputPath(), "demo/" + caseId + "/sagittal");
        String axialPath = valueOrDefault(request.axialInputPath(), "demo/" + caseId + "/axial");
        String sagittalModel = valueOrDefault(request.sagittalModelKey(), "sagittal_spider");
        String axialModel = valueOrDefault(request.axialModelKey(), "axial_t2_alkafri");
        Map<String, Object> metadata = new LinkedHashMap<>();
        if (request.metadata() != null) metadata.putAll(request.metadata());
        metadata.putIfAbsent("source", "backend-multiplanar-run");
        return new MultiplanarRunRequestDto(caseId, sagittalPath, axialPath, sagittalModel, axialModel, metadata);
    }

    private String valueOrDefault(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value.trim();
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
