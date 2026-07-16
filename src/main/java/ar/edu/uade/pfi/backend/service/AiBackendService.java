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
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

@Service
public class AiBackendService {
    static final long MAX_INPUT_UPLOAD_BYTES = 200L * 1024L * 1024L;
    private static final Set<String> ALLOWED_INPUT_EXTENSIONS = Set.of("npy", "png", "jpg", "jpeg", "bmp", "tif", "tiff", "mha", "mhd", "dcm");
    private static final Set<String> ALLOWED_INPUT_PLANES = Set.of("sagittal", "axial");
    private static final Set<String> VALID_REVIEW_STATUSES = Set.of("pendiente", "aceptado", "observado", "descartado");
    private static final Set<String> FINAL_REVIEW_STATUSES = Set.of("aceptado", "observado", "descartado");
    private final AiServiceOperations aiServiceClient;
    private final ReviewStoreService reviewStoreService;

    public AiBackendService(AiServiceOperations aiServiceClient, ReviewStoreService reviewStoreService) {
        this.aiServiceClient = aiServiceClient;
        this.reviewStoreService = reviewStoreService;
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
            return response;
        } catch (RuntimeException ex) {
            return normalizeForFrontend(Map.ofEntries(
                Map.entry("status", "ai_readiness_unavailable"),
                Map.entry("service", "pfi-ai-module"),
                Map.entry("readyForDemo", false),
                Map.entry("readyForRealInference", false),
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
                        "enabled", true,
                        "source", "backend_degraded_fallback"
                    ),
                    "axial_t2_alkafri", Map.of(
                        "plane", "axial",
                        "numClasses", 6,
                        "enabled", true,
                        "source", "backend_degraded_fallback"
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
        try {
            Map<String, Object> response = normalizeForFrontend(aiServiceClient.runPipeline(request));
            String runId = extractRunId(response);
            if (runId != null) {
                response.put("review", reviewStoreService.findOrDefault(runId));
            }
            response.put("aiModuleAvailable", true);
            response.put("degradedMode", false);
            return response;
        } catch (RuntimeException ex) {
            String runId = "degraded-" + Math.abs((request.caseId() + "|" + request.plane() + "|" + request.modelKey()).hashCode());
            return normalizeForFrontend(Map.ofEntries(
                Map.entry("runId", runId),
                Map.entry("caseId", request.caseId()),
                Map.entry("plane", request.plane()),
                Map.entry("modelKey", request.modelKey() == null ? "unknown" : request.modelKey()),
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
        return aiServiceClient.uploadInput(file, normalizedCaseId, normalizedPlane);
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
}
