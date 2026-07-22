package ar.edu.uade.pfi.backend.service;

import ar.edu.uade.pfi.backend.client.AiServiceOperations;
import ar.edu.uade.pfi.backend.dto.AiInputResponseDto;
import ar.edu.uade.pfi.backend.dto.AuditEventDto;
import ar.edu.uade.pfi.backend.dto.AuditEventRequestDto;
import ar.edu.uade.pfi.backend.dto.MeasurementBatchDto;
import ar.edu.uade.pfi.backend.dto.MeasurementSaveDto;
import ar.edu.uade.pfi.backend.dto.PipelineRunRequestDto;
import ar.edu.uade.pfi.backend.dto.ReviewExportRequestDto;
import ar.edu.uade.pfi.backend.dto.ReviewExportResponseDto;
import ar.edu.uade.pfi.backend.dto.ReviewSnapshotDto;
import ar.edu.uade.pfi.backend.dto.ReviewStatusDto;
import ar.edu.uade.pfi.backend.dto.ReviewUpdateRequestDto;
import ar.edu.uade.pfi.backend.util.ResponseNormalizer;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

@Service
public class AiBackendService {
    static final long MAX_INPUT_UPLOAD_BYTES = 200L * 1024L * 1024L;
    private static final Set<String> ALLOWED_INPUT_EXTENSIONS = Set.of("npy", "png", "jpg", "jpeg", "bmp", "tif", "tiff", "mha", "mhd", "dcm");
    private static final Set<String> ALLOWED_INPUT_PLANES = Set.of("sagittal", "axial");
    private static final Set<String> ALLOWED_ASSET_NAMES = Set.of("input.png", "overlay.png", "mask-preview.png");
    private static final Set<String> VALID_REVIEW_STATUSES = Set.of("pendiente", "aceptado", "observado", "descartado");
    private static final Set<String> FINAL_REVIEW_STATUSES = Set.of("aceptado", "observado", "descartado");
    private final AiServiceOperations aiServiceClient;
    private final ReviewStoreService reviewStoreService;
    private final AuditService auditService;
    private final PipelineRunRequestNormalizer pipelineRunRequestNormalizer;
    private final SagittalRealBaselineContractValidator sagittalContractValidator;
    private final AiPipelineResponsePresenter pipelineResponsePresenter;
    private final AiModelReadinessResolver modelReadinessResolver;

    public AiBackendService(AiServiceOperations aiServiceClient, ReviewStoreService reviewStoreService) {
        this(aiServiceClient, reviewStoreService, null, null, null, null, null);
    }

    public AiBackendService(AiServiceOperations aiServiceClient, ReviewStoreService reviewStoreService, AuditService auditService) {
        this(aiServiceClient, reviewStoreService, auditService, null, null, null, null);
    }

    @Autowired
    public AiBackendService(
        AiServiceOperations aiServiceClient,
        ReviewStoreService reviewStoreService,
        AuditService auditService,
        PipelineRunRequestNormalizer pipelineRunRequestNormalizer,
        SagittalRealBaselineContractValidator sagittalContractValidator,
        AiPipelineResponsePresenter pipelineResponsePresenter,
        AiModelReadinessResolver modelReadinessResolver
    ) {
        this.aiServiceClient = aiServiceClient;
        this.reviewStoreService = reviewStoreService;
        this.auditService = auditService;
        this.pipelineRunRequestNormalizer = pipelineRunRequestNormalizer;
        this.sagittalContractValidator = sagittalContractValidator;
        this.pipelineResponsePresenter = pipelineResponsePresenter;
        this.modelReadinessResolver = modelReadinessResolver;
    }

    public Map<String, Object> health() {
        try {
            Map<String, Object> response = normalizeForFrontend(aiServiceClient.health());
            response.put("status", response.getOrDefault("status", "ok"));
            response.put("backendStatus", "up");
            response.put("aiModuleAvailable", true);
            response.put("degradedMode", false);
            return response;
        } catch (RuntimeException ex) {
            return Map.of(
                "status", "degraded",
                "backendStatus", "up",
                "aiModuleAvailable", false,
                "degradedMode", true,
                "humanReviewRequired", true,
                "notClinicalDiagnosis", true,
                "message", ex.getMessage()
            );
        }
    }

    public Map<String, Object> readiness() {
        try {
            Map<String, Object> response = normalizeForFrontend(aiServiceClient.readiness());
            response.put("proxiedByBackend", true);
            response.put("aiModuleAvailable", true);
            response.put("degradedMode", false);
            response.put("backendReady", true);
            applyModelReadiness(response);
            return response;
        } catch (RuntimeException ex) {
            return normalizeForFrontend(Map.ofEntries(
                Map.entry("status", "ai_readiness_unavailable"),
                Map.entry("service", "pfi-ai-module"),
                Map.entry("backendReady", true),
                Map.entry("readyForDemo", false),
                Map.entry("readyForRealInference", false),
                Map.entry("sagittalReadyForRealInference", false),
                Map.entry("axialReadyForRealInference", false),
                Map.entry("defaultInferenceMode", "contract"),
                Map.entry("recommendedInferenceMode", "contract"),
                Map.entry("proxiedByBackend", true),
                Map.entry("aiModuleAvailable", false),
                Map.entry("degradedMode", true),
                Map.entry("message", ex.getMessage())
            ));
        }
    }

    public Object models() {
        try {
            return ResponseNormalizer.normalizeObject(aiServiceClient.models());
        } catch (RuntimeException ex) {
            return Map.of(
                "status", "degraded",
                "aiModuleAvailable", false,
                "degradedMode", true,
                "humanReviewRequired", true,
                "notClinicalDiagnosis", true,
                "message", ex.getMessage(),
                "models", Map.of(
                    "sagittal_spider", Map.of(
                        "plane", "sagittal",
                        "numClasses", 4,
                        "enabled", false,
                        "availableForRealInference", false,
                        "baselineReady", false,
                        "source", "ai_module_unavailable"
                    ),
                    "axial_t2_alkafri", Map.of(
                        "plane", "axial",
                        "numClasses", 6,
                        "enabled", false,
                        "availableForRealInference", false,
                        "baselineReady", false,
                        "source", "ai_module_unavailable"
                    )
                )
            );
        }
    }

    public Map<String, Object> verifyModels() {
        try {
            Map<String, Object> response = normalizeForFrontend(aiServiceClient.verifyModels());
            response.put("aiModuleAvailable", true);
            response.put("degradedMode", false);
            return response;
        } catch (RuntimeException ex) {
            return normalizeForFrontend(Map.of(
                "status", "model_artifact_verification_unavailable",
                "valid", false,
                "readyForRealInference", false,
                "defaultInferenceMode", "contract",
                "aiModuleAvailable", false,
                "degradedMode", true,
                "missingArtifacts", List.of(),
                "unverifiedArtifacts", List.of(),
                "verifiedModels", List.of(),
                "message", ex.getMessage()
            ));
        }
    }

    public Map<String, Object> runPipeline(PipelineRunRequestDto request) {
        PipelineRunRequestDto normalizedRequest = normalizePipelineRequest(request);
        boolean strict = isStrictRealBaseline(normalizedRequest);
        try {
            Map<String, Object> response = normalizeForFrontend(aiServiceClient.runPipeline(normalizedRequest));
            if (strict) {
                response = presentStrictPipelineResponse(normalizedRequest, response);
            }
            String runId = extractRunId(response);
            if (runId != null) {
                response.put("review", reviewStoreService.findOrDefault(runId));
            }
            response.put("aiModuleAvailable", true);
            response.put("degradedMode", false);
            if (strict) {
                auditStrictPipelineCompleted(normalizedRequest, response);
            }
            return response;
        } catch (RuntimeException ex) {
            if (strict) {
                auditStrictPipelineFailed(normalizedRequest, ex);
                throw ex;
            }
            String runId = "degraded-" + Math.abs((normalizedRequest.caseId() + "|" + normalizedRequest.plane() + "|" + normalizedRequest.modelKey()).hashCode());
            return normalizeForFrontend(Map.ofEntries(
                Map.entry("runId", runId),
                Map.entry("caseId", normalizedRequest.caseId()),
                Map.entry("plane", normalizedRequest.plane()),
                Map.entry("modelKey", normalizedRequest.modelKey() == null ? "unknown" : normalizedRequest.modelKey()),
                Map.entry("status", "pipeline_degraded_fallback"),
                Map.entry("aiModuleAvailable", false),
                Map.entry("degradedMode", true),
                Map.entry("agentDecision", Map.of(
                    "priority", "media",
                    "status", "requiere_revision",
                    "flags", List.of("ai_module_unavailable", "revision_profesional_requerida"),
                    "reasons", List.of("El backend no pudo completar la llamada al AI Module. Se mantiene la salida asistiva en modo degradado para validar arquitectura."),
                    "humanReviewRequired", true
                )),
                Map.entry("measurements", List.of(Map.of(
                    "id", "pipeline-status",
                    "label", "Estado del pipeline tecnico",
                    "value", "ai_module_unavailable",
                    "unit", ""
                ))),
                Map.entry("overlayPath", ""),
                Map.entry("review", reviewStoreService.findOrDefault(runId)),
                Map.entry("message", ex.getMessage())
            ));
        }
    }

    public AiInputResponseDto uploadInput(MultipartFile file, String caseId, String plane) {
        String normalizedCaseId = trimmed(caseId);
        String normalizedPlane = normalized(plane);
        validateInputUpload(file, normalizedCaseId, normalizedPlane);
        AiInputResponseDto response = aiServiceClient.uploadInput(file, normalizedCaseId, normalizedPlane);
        audit("backend", "upload.input.completed", response.inputId(), "", Map.of(
            "caseId", response.caseId(),
            "plane", response.plane(),
            "format", response.format(),
            "size", response.size()
        ));
        return response;
    }

    public ResponseEntity<byte[]> getAsset(String runId, String plane, String assetName) {
        String normalizedRunId = trimmed(runId);
        String normalizedPlane = normalized(plane);
        String normalizedAssetName = trimmed(assetName);
        validateAssetRequest(normalizedRunId, normalizedPlane, normalizedAssetName);
        ResponseEntity<byte[]> upstream = aiServiceClient.getAsset(normalizedRunId, normalizedPlane, normalizedAssetName);
        HttpHeaders headers = new HttpHeaders();
        String contentType = upstream.getHeaders().getFirst(HttpHeaders.CONTENT_TYPE);
        if (contentType != null && !contentType.isBlank()) {
            headers.add(HttpHeaders.CONTENT_TYPE, contentType);
        }
        return new ResponseEntity<>(upstream.getBody(), headers, upstream.getStatusCode());
    }

    public Map<String, Object> getAgentReport(String runId) {
        try {
            Map<String, Object> response = normalizeForFrontend(aiServiceClient.getAgentReport(runId));
            response.put("review", reviewStoreService.findOrDefault(runId));
            return response;
        } catch (RuntimeException ex) {
            return normalizeForFrontend(Map.of(
                "runId", runId,
                "caseId", "unknown",
                "status", "agent_report_unavailable",
                "aiModuleAvailable", false,
                "degradedMode", true,
                "agentDecision", Map.of(
                    "priority", "media",
                    "status", "requiere_revision",
                    "flags", List.of("agent_report_unavailable", "revision_profesional_requerida"),
                    "reasons", List.of("El AI Module no devolvio un reporte persistido para este runId; se conserva revision profesional obligatoria."),
                    "humanReviewRequired", true
                ),
                "measurements", List.of(),
                "review", reviewStoreService.findOrDefault(runId),
                "message", ex.getMessage()
            ));
        }
    }

    public Map<String, Object> getAgentReportSummary(String runId) {
        try {
            Map<String, Object> response = normalizeForFrontend(aiServiceClient.getAgentReportSummary(runId));
            response.put("review", reviewStoreService.findOrDefault(runId));
            response.put("summaryOnly", true);
            return response;
        } catch (RuntimeException ex) {
            return normalizeForFrontend(Map.of(
                "runId", runId,
                "status", "agent_report_summary_unavailable",
                "aiModuleAvailable", false,
                "degradedMode", true,
                "summaryOnly", true,
                "review", reviewStoreService.findOrDefault(runId),
                "message", ex.getMessage()
            ));
        }
    }

    public Map<String, Object> getRecentAgentReports(int limit) {
        try {
            Map<String, Object> response = normalizeForFrontend(aiServiceClient.getRecentAgentReports(limit));
            response.put("summaryOnly", true);
            return response;
        } catch (RuntimeException ex) {
            return normalizeForFrontend(Map.of(
                "status", "agent_reports_unavailable",
                "aiModuleAvailable", false,
                "degradedMode", true,
                "summaryOnly", true,
                "count", 0,
                "items", List.of(),
                "message", ex.getMessage()
            ));
        }
    }

    public ReviewStatusDto updateReview(String runId, ReviewUpdateRequestDto request) {
        ReviewUpdateRequestDto normalizedRequest = validateReviewDecision(request);
        return reviewStoreService.updateReview(runId, normalizedRequest);
    }

    public ReviewSnapshotDto reviewHistory() {
        return reviewStoreService.snapshot();
    }

    public List<MeasurementSaveDto> getMeasurements(String runId) {
        return reviewStoreService.findMeasurements(runId);
    }

    public List<MeasurementSaveDto> saveMeasurements(String runId, MeasurementBatchDto request) {
        return reviewStoreService.saveMeasurements(runId, request);
    }

    public ReviewExportResponseDto exportReview(String runId, ReviewExportRequestDto request) {
        return reviewStoreService.exportReview(runId, request);
    }

    public AuditEventDto appendAudit(AuditEventRequestDto request) {
        return reviewStoreService.appendAudit(request);
    }

    public List<AuditEventDto> auditTrail() {
        return reviewStoreService.auditTrail();
    }

    private ReviewUpdateRequestDto validateReviewDecision(ReviewUpdateRequestDto request) {
        String status = normalized(request.status());
        String notes = trimmed(request.notes());
        String reviewer = trimmed(request.reviewer());
        if (!VALID_REVIEW_STATUSES.contains(status)) {
            throw badRequest("Estado de revision invalido. Valores permitidos: pendiente, aceptado, observado, descartado.");
        }
        if (FINAL_REVIEW_STATUSES.contains(status) && reviewer.isBlank()) {
            throw badRequest("La decision profesional requiere reviewer identificado.");
        }
        if (("observado".equals(status) || "descartado".equals(status)) && notes.length() < 5) {
            throw badRequest("Los estados observado o descartado requieren una nota profesional descriptiva.");
        }
        return new ReviewUpdateRequestDto(status, notes, reviewer);
    }

    private void validateInputUpload(MultipartFile file, String caseId, String plane) {
        if (file == null || file.isEmpty()) {
            throw badRequest("El archivo de input es obligatorio y no puede estar vacio.");
        }
        if (caseId.isBlank()) {
            throw badRequest("caseId es obligatorio.");
        }
        if (!ALLOWED_INPUT_PLANES.contains(plane)) {
            throw badRequest("Plano invalido. Valores permitidos: sagittal, axial.");
        }
        if (file.getSize() > MAX_INPUT_UPLOAD_BYTES) {
            throw badRequest("El archivo supera el tamano maximo permitido por el backend.");
        }
        String extension = inputExtension(file.getOriginalFilename());
        if (!ALLOWED_INPUT_EXTENSIONS.contains(extension)) {
            throw badRequest("Formato de input invalido. Extensiones permitidas: .npy,.png,.jpg,.jpeg,.bmp,.tif,.tiff,.mha,.mhd,.dcm.");
        }
    }

    private String inputExtension(String originalFilename) {
        String filename = StringUtils.getFilename(originalFilename == null ? "" : originalFilename);
        int dotIndex = filename == null ? -1 : filename.lastIndexOf('.');
        return dotIndex < 0 ? "" : filename.substring(dotIndex + 1).toLowerCase(Locale.ROOT);
    }

    private void validateAssetRequest(String runId, String plane, String assetName) {
        if (runId.isBlank()) {
            throw badRequest("runId es obligatorio.");
        }
        if (!ALLOWED_INPUT_PLANES.contains(plane)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Asset no encontrado.");
        }
        if (!isSimpleBasename(assetName) || !ALLOWED_ASSET_NAMES.contains(assetName)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Asset no permitido.");
        }
    }

    private boolean isSimpleBasename(String assetName) {
        if (assetName.isBlank() || assetName.contains("/") || assetName.contains("\\") || assetName.contains("..")) {
            return false;
        }
        return assetName.equals(StringUtils.getFilename(assetName));
    }

    private ResponseStatusException badRequest(String message) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, message);
    }

    private String normalized(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
    }

    private String trimmed(String value) {
        return value == null ? "" : value.trim();
    }

    private Map<String, Object> normalizeForFrontend(Map<String, Object> response) {
        Map<String, Object> normalized = ResponseNormalizer.normalizeMap(response);
        normalized.put("humanReviewRequired", true);
        normalized.put("notClinicalDiagnosis", true);
        return normalized;
    }

    private String extractRunId(Map<String, Object> response) {
        Object runId = response.get("runId");
        return runId == null ? null : runId.toString();
    }

    private void audit(String actor, String action, String entityId, String traceId, Map<String, Object> metadata) {
        if (auditService == null) return;
        auditService.record(actor, action, entityId, traceId, metadata);
    }

    private void applyModelReadiness(Map<String, Object> readinessResponse) {
        if (modelReadinessResolver == null) {
            readinessResponse.put("sagittalReadyForRealInference", false);
            readinessResponse.put("axialReadyForRealInference", false);
            return;
        }
        try {
            Map<String, Object> verification = normalizeForFrontend(aiServiceClient.verifyModels());
            AiModelReadinessResolver.ModelReadiness readiness = modelReadinessResolver.resolve(verification);
            readinessResponse.put("sagittalReadyForRealInference", readiness.sagittalReadyForRealInference());
            readinessResponse.put("axialReadyForRealInference", readiness.axialReadyForRealInference());
        } catch (RuntimeException ex) {
            readinessResponse.put("degradedMode", true);
            readinessResponse.put("sagittalReadyForRealInference", false);
            readinessResponse.put("axialReadyForRealInference", false);
            readinessResponse.put("readyForRealInference", false);
            readinessResponse.put("modelVerificationStatus", "model_artifact_verification_unavailable");
            readinessResponse.put("modelVerificationMessage", ex.getMessage());
        }
    }

    private PipelineRunRequestDto normalizePipelineRequest(PipelineRunRequestDto request) {
        if (pipelineRunRequestNormalizer == null) {
            return request;
        }
        return pipelineRunRequestNormalizer.normalizePipelineRequest(request);
    }

    private boolean isStrictRealBaseline(PipelineRunRequestDto request) {
        return pipelineRunRequestNormalizer != null && pipelineRunRequestNormalizer.isStrictRealBaseline(request);
    }

    private Map<String, Object> presentStrictPipelineResponse(PipelineRunRequestDto request, Map<String, Object> response) {
        if (sagittalContractValidator != null) {
            sagittalContractValidator.validatePipelineResponse(request, response);
        }
        return pipelineResponsePresenter == null ? response : pipelineResponsePresenter.present(response);
    }

    private void auditStrictPipelineCompleted(PipelineRunRequestDto request, Map<String, Object> response) {
        Map<String, Object> metadata = metadataMap(response);
        audit("backend", "pipeline.real_baseline.completed", extractRunId(response), text(response.get("traceId")), Map.ofEntries(
            Map.entry("runId", text(response.get("runId"))),
            Map.entry("caseId", request.caseId()),
            Map.entry("plane", request.plane()),
            Map.entry("modelKey", text(response.get("modelKey"))),
            Map.entry("modelVersion", text(response.get("modelVersion"))),
            Map.entry("artifactHash", text(response.get("artifactHash"))),
            Map.entry("inferenceMode", text(response.get("inferenceMode"))),
            Map.entry("selectedSlice", metadata.getOrDefault("selectedSlice", "")),
            Map.entry("selectedAxis", metadata.getOrDefault("selectedAxis", "")),
            Map.entry("sliceCount", metadata.getOrDefault("sliceCount", "")),
            Map.entry("inputOrientationTransform", metadata.getOrDefault("inputOrientationTransform", "")),
            Map.entry("traceId", text(response.get("traceId"))),
            Map.entry("humanReviewRequired", response.getOrDefault("humanReviewRequired", true))
        ));
    }

    private void auditStrictPipelineFailed(PipelineRunRequestDto request, RuntimeException ex) {
        audit("backend", "pipeline.real_baseline.failed", request.caseId(), "", Map.of(
            "caseId", request.caseId(),
            "plane", request.plane(),
            "modelKey", request.modelKey(),
            "inferenceMode", "real_baseline",
            "errorType", ex.getClass().getSimpleName()
        ));
    }

    private Map<String, Object> metadataMap(Map<String, Object> response) {
        Object metadata = response.get("metadata");
        if (metadata instanceof Map<?, ?> map) {
            @SuppressWarnings("unchecked")
            Map<String, Object> typed = (Map<String, Object>) map;
            return typed;
        }
        return Map.of();
    }

    private String text(Object value) {
        return value == null ? "" : value.toString();
    }
}
