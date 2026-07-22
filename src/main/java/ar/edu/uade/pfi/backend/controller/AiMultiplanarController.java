package ar.edu.uade.pfi.backend.controller;

import ar.edu.uade.pfi.backend.client.AiServiceOperations;
import ar.edu.uade.pfi.backend.dto.MultiplanarRunRequestDto;
import ar.edu.uade.pfi.backend.dto.MultiplanarRunResponseDto;
import ar.edu.uade.pfi.backend.service.AuditService;
import ar.edu.uade.pfi.backend.service.MultiplanarRealBaselineContractValidator;
import ar.edu.uade.pfi.backend.service.MultiplanarRunPersistenceService;
import ar.edu.uade.pfi.backend.service.MultiplanarRunResponsePresenter;
import jakarta.validation.Valid;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/ai/multiplanar")
public class AiMultiplanarController {
    private final AiServiceOperations aiServiceClient;
    private final MultiplanarRunPersistenceService persistenceService;
    private final AuditService auditService;
    private final MultiplanarRunResponsePresenter presenter;
    private final MultiplanarRealBaselineContractValidator validator;

    public AiMultiplanarController(AiServiceOperations aiServiceClient) {
        this(aiServiceClient, null, null, new MultiplanarRunResponsePresenter(), new MultiplanarRealBaselineContractValidator());
    }

    public AiMultiplanarController(AiServiceOperations aiServiceClient, MultiplanarRunPersistenceService persistenceService) {
        this(aiServiceClient, persistenceService, null, new MultiplanarRunResponsePresenter(), new MultiplanarRealBaselineContractValidator());
    }

    @Autowired
    public AiMultiplanarController(AiServiceOperations aiServiceClient, MultiplanarRunPersistenceService persistenceService, AuditService auditService) {
        this(aiServiceClient, persistenceService, auditService, new MultiplanarRunResponsePresenter(), new MultiplanarRealBaselineContractValidator());
    }

    AiMultiplanarController(
        AiServiceOperations aiServiceClient,
        MultiplanarRunPersistenceService persistenceService,
        AuditService auditService,
        MultiplanarRunResponsePresenter presenter,
        MultiplanarRealBaselineContractValidator validator
    ) {
        this.aiServiceClient = aiServiceClient;
        this.persistenceService = persistenceService;
        this.auditService = auditService;
        this.presenter = presenter;
        this.validator = validator;
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
        MultiplanarRunResponseDto response;
        try {
            response = aiServiceClient.runMultiplanar(normalized);
        } catch (RuntimeException ex) {
            auditStrictFailure(normalized, ex.getMessage());
            throw ex;
        }
        MultiplanarRunResponseDto presented = presenter.present(response);
        try {
            validator.validate(normalized, presented);
        } catch (RuntimeException ex) {
            auditStrictFailure(normalized, ex.getMessage());
            throw ex;
        }
        if (persistenceService != null) {
            persistenceService.persistSuccessfulRun(normalized, presented);
        }
        auditSuccess(normalized, presented);
        return presented;
    }

    private MultiplanarRunRequestDto normalizedRequest(MultiplanarRunRequestDto request) {
        String caseId = request.caseId().trim();
        String sagittalModel = valueOrDefault(request.sagittalModelKey(), "sagittal_spider");
        String axialModel = valueOrDefault(request.axialModelKey(), "axial_t2_alkafri");
        Map<String, Object> metadata = new LinkedHashMap<>();
        if (request.metadata() != null) metadata.putAll(request.metadata());
        if (request.allowContractFallback() != null) metadata.put("allowContractFallback", request.allowContractFallback());
        metadata.putIfAbsent("source", "backend-multiplanar-run");
        boolean realBaseline = "real_baseline".equals(String.valueOf(metadata.getOrDefault("inferenceMode", "")).trim());
        boolean strict = realBaseline && (Boolean.FALSE.equals(request.allowContractFallback()) || Boolean.FALSE.equals(metadata.get("allowContractFallback")));
        if (strict) {
            validateStrictInputs(request);
            sagittalModel = "sagittal_spider";
            axialModel = "axial_t2_alkafri";
            metadata.put("inferenceMode", "real_baseline");
            metadata.put("requestedInferenceMode", "real_baseline");
            metadata.put("allowContractFallback", false);
        }
        return new MultiplanarRunRequestDto(
            caseId,
            valueOrNull(request.sagittalInputId()),
            valueOrNull(request.axialInputId()),
            valueOrNull(request.sagittalInputPath()),
            valueOrNull(request.axialInputPath()),
            sagittalModel,
            axialModel,
            strict ? false : request.allowContractFallback(),
            metadata
        );
    }

    private void validateStrictInputs(MultiplanarRunRequestDto request) {
        requireStrict(valueOrNull(request.sagittalInputId()) != null, "sagittalInputId es obligatorio para real_baseline estricto.");
        requireStrict(valueOrNull(request.axialInputId()) != null, "axialInputId es obligatorio para real_baseline estricto dual.");
        requireStrict(!(valueOrNull(request.sagittalInputId()) != null && valueOrNull(request.sagittalInputPath()) != null), "Enviar solamente sagittalInputId o sagittalInputPath, no ambos.");
        requireStrict(!(valueOrNull(request.axialInputId()) != null && valueOrNull(request.axialInputPath()) != null), "Enviar solamente axialInputId o axialInputPath, no ambos.");
        requireStrict(!startsDemo(request.sagittalInputId()) && !startsDemo(request.sagittalInputPath()), "Inputs demo no permitidos para sagital real_baseline estricto.");
        requireStrict(!startsDemo(request.axialInputId()) && !startsDemo(request.axialInputPath()), "Inputs demo no permitidos para axial real_baseline estricto.");
    }

    private void requireStrict(boolean condition, String message) {
        if (!condition) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, message);
    }

    private boolean startsDemo(String value) {
        return value != null && value.trim().startsWith("demo/");
    }

    private void auditSuccess(MultiplanarRunRequestDto request, MultiplanarRunResponseDto response) {
        if (auditService == null) return;
        MultiplanarRunResponseDto.PlaneDto sagittal = response.planes() == null ? null : response.planes().sagittal();
        MultiplanarRunResponseDto.PlaneDto axial = response.planes() == null ? null : response.planes().axial();
        String action = validator.isStrict(request) ? "multiplanar.real_baseline.completed" : "multiplanar.run.completed";
        auditService.record("backend", action, response.runId(), response.traceId(), auditMetadata(request, response, sagittal, axial));
    }

    private void auditStrictFailure(MultiplanarRunRequestDto request, String message) {
        if (auditService == null || !validator.isStrict(request)) return;
        auditService.record("backend", "multiplanar.real_baseline.failed", request.caseId(), traceId(request), Map.of(
            "caseId", request.caseId(),
            "traceId", traceId(request),
            "sagittalInputIdPresent", request.sagittalInputId() != null,
            "axialInputIdPresent", request.axialInputId() != null,
            "message", message == null ? "strict multiplanar failed" : message
        ));
    }

    private Map<String, Object> auditMetadata(MultiplanarRunRequestDto request, MultiplanarRunResponseDto response, MultiplanarRunResponseDto.PlaneDto sagittal, MultiplanarRunResponseDto.PlaneDto axial) {
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("runId", response.runId());
        metadata.put("sagittalRunId", sagittal == null ? "" : sagittal.runId());
        metadata.put("axialRunId", axial == null ? "" : axial.runId());
        metadata.put("caseId", request.caseId());
        metadata.put("sagittalModelKey", sagittal == null ? request.sagittalModelKey() : sagittal.modelKey());
        metadata.put("sagittalModelVersion", sagittal == null ? "" : sagittal.modelVersion());
        metadata.put("sagittalArtifactHash", sagittal == null ? "" : sagittal.artifactHash());
        metadata.put("sagittalInferenceMode", sagittal == null ? "" : sagittal.effectiveInferenceMode());
        metadata.put("axialModelKey", axial == null ? request.axialModelKey() : axial.modelKey());
        metadata.put("axialInferenceMode", axial == null ? "" : axial.effectiveInferenceMode());
        metadata.put("sagittalInputIdPresent", request.sagittalInputId() != null);
        metadata.put("axialInputIdPresent", request.axialInputId() != null);
        metadata.put("traceId", response.traceId());
        metadata.put("humanReviewRequired", response.humanReviewRequired());
        if (sagittal != null && sagittal.metadata() != null) {
            metadata.put("sagittalSelectedSlice", sagittal.metadata().get("selectedSlice"));
            metadata.put("sagittalSelectedAxis", sagittal.metadata().get("selectedAxis"));
            metadata.put("sagittalSliceCount", sagittal.metadata().get("sliceCount"));
            metadata.put("sagittalOrientationTransform", sagittal.metadata().get("inputOrientationTransform"));
        }
        return metadata;
    }

    private String traceId(MultiplanarRunRequestDto request) {
        if (request.metadata() == null) return "";
        Object value = request.metadata().get("traceId");
        return value == null ? "" : String.valueOf(value);
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
