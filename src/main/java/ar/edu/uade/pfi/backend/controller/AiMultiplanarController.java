package ar.edu.uade.pfi.backend.controller;

import ar.edu.uade.pfi.backend.client.AiServiceOperations;
import ar.edu.uade.pfi.backend.domain.CanonicalMultiplanarRun;
import ar.edu.uade.pfi.backend.domain.CanonicalPlaneRun;
import ar.edu.uade.pfi.backend.dto.MultiplanarRunApiRequestDto;
import ar.edu.uade.pfi.backend.dto.MultiplanarRunRequestDto;
import ar.edu.uade.pfi.backend.dto.MultiplanarRunResponseDto;
import ar.edu.uade.pfi.backend.service.AuditService;
import ar.edu.uade.pfi.backend.service.CanonicalMultiplanarRunLegacyPresenter;
import ar.edu.uade.pfi.backend.service.MultiplanarRealBaselineContractValidator;
import ar.edu.uade.pfi.backend.service.MultiplanarRunPersistenceService;
import ar.edu.uade.pfi.backend.service.MultiplanarRunResponsePresenter;
import ar.edu.uade.pfi.backend.service.StudyRunService.PreparedStudyMetadata;
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
    private final CanonicalMultiplanarRunLegacyPresenter legacyPresenter;
    private final MultiplanarRealBaselineContractValidator strictnessClassifier;

    public AiMultiplanarController(AiServiceOperations aiServiceClient) {
        this(aiServiceClient, null, null, new MultiplanarRunResponsePresenter(), new CanonicalMultiplanarRunLegacyPresenter(), new MultiplanarRealBaselineContractValidator());
    }

    public AiMultiplanarController(AiServiceOperations aiServiceClient, MultiplanarRunPersistenceService persistenceService) {
        this(aiServiceClient, persistenceService, null, new MultiplanarRunResponsePresenter(), new CanonicalMultiplanarRunLegacyPresenter(), new MultiplanarRealBaselineContractValidator());
    }

    @Autowired
    public AiMultiplanarController(AiServiceOperations aiServiceClient, MultiplanarRunPersistenceService persistenceService, AuditService auditService) {
        this(aiServiceClient, persistenceService, auditService, new MultiplanarRunResponsePresenter(), new CanonicalMultiplanarRunLegacyPresenter(), new MultiplanarRealBaselineContractValidator());
    }

    AiMultiplanarController(
        AiServiceOperations aiServiceClient,
        MultiplanarRunPersistenceService persistenceService,
        AuditService auditService,
        MultiplanarRunResponsePresenter presenter,
        CanonicalMultiplanarRunLegacyPresenter legacyPresenter,
        MultiplanarRealBaselineContractValidator strictnessClassifier
    ) {
        this.aiServiceClient = aiServiceClient;
        this.persistenceService = persistenceService;
        this.auditService = auditService;
        this.presenter = presenter;
        this.legacyPresenter = legacyPresenter;
        this.strictnessClassifier = strictnessClassifier;
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
    public MultiplanarRunResponseDto run(@Valid @RequestBody MultiplanarRunApiRequestDto request) {
        PreparedStudyMetadata preparedMetadata = persistenceService == null
            ? null
            : persistenceService.prepareStudyMetadata(request.caseId(), request.studyMetadata());
        MultiplanarRunRequestDto normalized = normalizedRequest(request, preparedMetadata);
        // AiServiceClient already resolves the contract version, calls exactly one
        // endpoint, validates strict real_baseline requests, and returns the canonical
        // model — this controller no longer touches AI Module DTOs directly.
        CanonicalMultiplanarRun canonical;
        try {
            canonical = aiServiceClient.runMultiplanar(normalized);
        } catch (RuntimeException ex) {
            auditStrictFailure(normalized, ex.getMessage());
            throw ex;
        }
        MultiplanarRunResponseDto presented = presenter.present(legacyPresenter.toLegacyResponse(canonical));
        if (persistenceService != null) {
            persistenceService.persistSuccessfulRun(normalized, preparedMetadata.metadata(), canonical);
        }
        auditSuccess(normalized, canonical);
        return presented;
    }

    private MultiplanarRunRequestDto normalizedRequest(MultiplanarRunApiRequestDto request, PreparedStudyMetadata preparedMetadata) {
        String caseId = preparedMetadata == null ? request.caseId().trim() : preparedMetadata.caseId();
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
            boolean axialPresent = valueOrNull(request.axialInputId()) != null || valueOrNull(request.axialInputPath()) != null;
            metadata.put("inferenceMode", "real_baseline");
            metadata.put("requestedInferenceMode", "real_baseline");
            metadata.put("allowContractFallback", false);
            metadata.putIfAbsent("axialMode", axialPresent ? "optional_provided" : "optional_not_provided");
        }
        return new MultiplanarRunRequestDto(
            caseId,
            valueOrNull(request.sagittalInputId()),
            valueOrNull(request.axialInputId()),
            valueOrNull(request.sagittalInputPath()),
            valueOrNull(request.axialInputPath()),
            sagittalModel,
            axialModel,
            strict ? Boolean.FALSE : request.allowContractFallback(),
            metadata
        );
    }

    private void validateStrictInputs(MultiplanarRunApiRequestDto request) {
        boolean sagittalInputIdPresent = valueOrNull(request.sagittalInputId()) != null;
        boolean sagittalInputPathPresent = valueOrNull(request.sagittalInputPath()) != null;
        boolean axialInputIdPresent = valueOrNull(request.axialInputId()) != null;
        boolean axialInputPathPresent = valueOrNull(request.axialInputPath()) != null;

        requireStrict(sagittalInputIdPresent || sagittalInputPathPresent, "sagittalInputId o sagittalInputPath es obligatorio para real_baseline estricto.");
        requireStrict(!(sagittalInputIdPresent && sagittalInputPathPresent), "Enviar solamente sagittalInputId o sagittalInputPath, no ambos.");
        requireStrict(!(axialInputIdPresent && axialInputPathPresent), "Enviar solamente axialInputId o axialInputPath, no ambos.");
        requireStrict(!startsDemo(request.sagittalInputId()) && !startsDemo(request.sagittalInputPath()), "Inputs demo no permitidos para sagital real_baseline estricto.");
        if (axialInputIdPresent || axialInputPathPresent) {
            requireStrict(!startsDemo(request.axialInputId()) && !startsDemo(request.axialInputPath()), "Inputs demo no permitidos para axial real_baseline estricto.");
        }
    }

    private void requireStrict(boolean condition, String message) {
        if (!condition) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, message);
    }

    private boolean startsDemo(String value) {
        return value != null && value.trim().startsWith("demo/");
    }

    private void auditSuccess(MultiplanarRunRequestDto request, CanonicalMultiplanarRun response) {
        if (auditService == null) return;
        CanonicalPlaneRun sagittal = response.sagittal();
        CanonicalPlaneRun axial = response.axial();
        String action = strictnessClassifier.isStrict(request) ? "multiplanar.real_baseline.completed" : "multiplanar.run.completed";
        auditService.record("backend", action, response.multiplanarRunId(), response.traceId(), auditMetadata(request, response, sagittal, axial));
    }

    private void auditStrictFailure(MultiplanarRunRequestDto request, String message) {
        if (auditService == null || !strictnessClassifier.isStrict(request)) return;
        auditService.record("backend", "multiplanar.real_baseline.failed", request.caseId(), traceId(request), Map.of(
            "caseId", request.caseId(),
            "traceId", traceId(request),
            "sagittalInputIdPresent", request.sagittalInputId() != null,
            "axialInputIdPresent", request.axialInputId() != null,
            "message", message == null ? "strict multiplanar failed" : message
        ));
    }

    private Map<String, Object> auditMetadata(MultiplanarRunRequestDto request, CanonicalMultiplanarRun response, CanonicalPlaneRun sagittal, CanonicalPlaneRun axial) {
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("runId", response.multiplanarRunId());
        metadata.put("sagittalRunId", sagittal == null ? "" : sagittal.planeRunId());
        metadata.put("axialRunId", axial == null ? "" : axial.planeRunId());
        metadata.put("caseId", request.caseId());
        metadata.put("sagittalModelKey", sagittal == null ? request.sagittalModelKey() : modelKey(sagittal.model()));
        metadata.put("sagittalModelVersion", sagittal == null ? "" : modelVersion(sagittal.model()));
        metadata.put("sagittalArtifactHash", sagittal == null ? "" : String.valueOf(sagittal.model().get("artifactHash")));
        metadata.put("sagittalInferenceMode", sagittal == null ? "" : sagittal.effectiveInferenceMode());
        metadata.put("axialModelKey", axial == null ? request.axialModelKey() : modelKey(axial.model()));
        metadata.put("axialInferenceMode", axial == null ? "" : axial.effectiveInferenceMode());
        metadata.put("sagittalInputIdPresent", request.sagittalInputId() != null);
        metadata.put("axialInputIdPresent", request.axialInputId() != null);
        metadata.put("axialInferenceRequested", request.axialInputId() != null || request.axialInputPath() != null);
        metadata.put("dualRunReady", axial != null && "real_baseline".equals(axial.effectiveInferenceMode()));
        metadata.put("traceId", response.traceId());
        metadata.put("humanReviewRequired", response.governance().humanReviewRequired());
        return metadata;
    }

    /**
     * Model key/version live under different map keys depending on the upstream contract:
     * v2 stores the wire names "key"/"version" as-is, v1 stores "modelKey"/"modelVersion".
     */
    private String modelKey(Map<String, Object> model) {
        Object value = model.containsKey("key") ? model.get("key") : model.get("modelKey");
        return String.valueOf(value);
    }

    private String modelVersion(Map<String, Object> model) {
        Object value = model.containsKey("version") ? model.get("version") : model.get("modelVersion");
        return String.valueOf(value);
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
