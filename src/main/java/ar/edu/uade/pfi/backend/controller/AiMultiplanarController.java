package ar.edu.uade.pfi.backend.controller;

import ar.edu.uade.pfi.backend.client.AiServiceOperations;
import ar.edu.uade.pfi.backend.dto.MultiplanarRunRequestDto;
import ar.edu.uade.pfi.backend.dto.MultiplanarRunResponseDto;
import ar.edu.uade.pfi.backend.service.AuditService;
import ar.edu.uade.pfi.backend.service.MultiplanarRunPersistenceService;
import jakarta.validation.Valid;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/ai/multiplanar")
public class AiMultiplanarController {
    private final AiServiceOperations aiServiceClient;
    private final MultiplanarRunPersistenceService persistenceService;
    private final AuditService auditService;

    public AiMultiplanarController(AiServiceOperations aiServiceClient) {
        this(aiServiceClient, null, null);
    }

    public AiMultiplanarController(AiServiceOperations aiServiceClient, MultiplanarRunPersistenceService persistenceService) {
        this(aiServiceClient, persistenceService, null);
    }

    @Autowired
    public AiMultiplanarController(AiServiceOperations aiServiceClient, MultiplanarRunPersistenceService persistenceService, AuditService auditService) {
        this.aiServiceClient = aiServiceClient;
        this.persistenceService = persistenceService;
        this.auditService = auditService;
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
    public MultiplanarRunResponseDto run(@Valid @RequestBody MultiplanarRunRequestDto request) {
        MultiplanarRunRequestDto normalized = normalizedRequest(request);
        MultiplanarRunResponseDto response = aiServiceClient.runMultiplanar(normalized);
        if (persistenceService != null) {
            persistenceService.persistSuccessfulRun(normalized, response);
        }
        if (auditService != null) {
            auditService.record("backend", "multiplanar.run.completed", response.runId(), response.traceId(), Map.of(
                "caseId", normalized.caseId(),
                "requestedInferenceMode", String.valueOf(normalized.metadata().getOrDefault("inferenceMode", "")),
                "effectiveInferenceMode", String.valueOf(response.effectiveInferenceMode()),
                "sagittalInputIdPresent", normalized.sagittalInputId() != null,
                "axialInputIdPresent", normalized.axialInputId() != null
            ));
        }
        return response;
    }

    private MultiplanarRunRequestDto normalizedRequest(MultiplanarRunRequestDto request) {
        String caseId = request.caseId().trim();
        String sagittalModel = valueOrDefault(request.sagittalModelKey(), "sagittal_spider");
        String axialModel = valueOrDefault(request.axialModelKey(), "axial_t2_alkafri");
        Map<String, Object> metadata = new LinkedHashMap<>();
        if (request.metadata() != null) metadata.putAll(request.metadata());
        if (request.allowContractFallback() != null) metadata.put("allowContractFallback", request.allowContractFallback());
        metadata.putIfAbsent("source", "backend-multiplanar-run");
        return new MultiplanarRunRequestDto(
            caseId,
            valueOrNull(request.sagittalInputId()),
            valueOrNull(request.axialInputId()),
            valueOrNull(request.sagittalInputPath()),
            valueOrNull(request.axialInputPath()),
            sagittalModel,
            axialModel,
            request.allowContractFallback(),
            metadata
        );
    }

    private String valueOrDefault(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value.trim();
    }

    private String valueOrNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
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
