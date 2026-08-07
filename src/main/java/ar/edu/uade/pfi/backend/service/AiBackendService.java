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
import ar.edu.uade.pfi.backend.dto.RunReviewRequestDto;
import ar.edu.uade.pfi.backend.dto.RunReviewResponseDto;
import ar.edu.uade.pfi.backend.dto.StudyUploadInputDto;
import ar.edu.uade.pfi.backend.dto.StudyUploadResponseDto;
import ar.edu.uade.pfi.backend.dto.StudyUploadSeriesDto;
import ar.edu.uade.pfi.backend.config.AiServiceProperties;
import ar.edu.uade.pfi.backend.config.SafeLogSanitizer;
import ar.edu.uade.pfi.backend.repository.StudyRepository;
import ar.edu.uade.pfi.backend.util.ResponseNormalizer;
import ar.edu.uade.pfi.backend.domain.RunArtifact;
import ar.edu.uade.pfi.backend.domain.RunAssetContent;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

@Service
public class AiBackendService {
    static final long MAX_INPUT_UPLOAD_BYTES = 200L * 1024L * 1024L;
    /**
     * Upload formats the backend forwards to the AI Module.
     *
     * <p>It must not be narrower than the AI Module's own allowlist, or the backend
     * rejects a file the module could have read — which is what happened with whole
     * series: {@code .zip} is how a multi-slice study travels, and {@code .ima} is
     * what a Siemens scanner writes, and neither reached the module.
     */
    private static final Set<String> ALLOWED_INPUT_EXTENSIONS =
            Set.of("npy", "png", "jpg", "jpeg", "bmp", "tif", "tiff", "mha", "mhd", "dcm", "ima", "zip");
    private static final Set<String> ALLOWED_INPUT_PLANES = Set.of("sagittal", "axial");
    /**
     * Planes a series can be listed and displayed under.
     *
     * <p>Wider than {@link #ALLOWED_INPUT_PLANES} on purpose: that one guards what a
     * model may run on, this one guards what the reader may look at. {@code unknown}
     * is the series whose header carries no orientation, and a localizer, which has no
     * single plane to report.
     */
    private static final Set<String> VIEWABLE_PLANES = Set.of("sagittal", "axial", "coronal", "unknown");
    private static final Set<String> ALLOWED_ASSET_NAMES = Set.of("input.png", "overlay.png", "mask-preview.png", "lumbar-3d-mesh.json");
    static final long DEFAULT_STUDY_UPLOAD_BYTES = 200L * 1024L * 1024L;
    /**
     * Content types accepted for a whole-study archive.
     *
     * <p>The empty string is included on purpose: a browser that cannot classify the
     * file sends no content type at all, and rejecting that would turn a valid study
     * away over a header the client never had to send. The extension is checked
     * separately, so this widens nothing on its own.
     */
    private static final Set<String> ALLOWED_STUDY_CONTENT_TYPES = Set.of("", "application/zip", "application/x-zip-compressed", "application/octet-stream");
    private static final Pattern CASE_ID_PATTERN = Pattern.compile("^[A-Za-z0-9][A-Za-z0-9._:-]{0,79}$");
    private static final Set<String> VALID_REVIEW_STATUSES = Set.of("pendiente", "aceptado", "observado", "descartado");
    private static final Set<String> FINAL_REVIEW_STATUSES = Set.of("aceptado", "observado", "descartado");
    private final AiServiceOperations aiServiceClient;
    private final ReviewStoreService reviewStoreService;
    private final AuditService auditService;
    private final PipelineRunRequestNormalizer pipelineRunRequestNormalizer;
    private final SagittalRealBaselineContractValidator sagittalContractValidator;
    private final AiPipelineResponsePresenter pipelineResponsePresenter;
    private final AiModelReadinessResolver modelReadinessResolver;
    private final StudyRepository studyRepository;
    private final RunAssetContentStorage assetContentStorage;
    private final RunAssetSnapshotService runAssetSnapshotService;
    private final RunReviewService runReviewService;
    private final long studyUploadMaxBytes;

    public AiBackendService(AiServiceOperations aiServiceClient, ReviewStoreService reviewStoreService) {
        this(aiServiceClient, reviewStoreService, null, null, null, null, null, null, (RunAssetContentStorage) null, null, null, DEFAULT_STUDY_UPLOAD_BYTES);
    }

    public AiBackendService(AiServiceOperations aiServiceClient, ReviewStoreService reviewStoreService, AuditService auditService) {
        this(aiServiceClient, reviewStoreService, auditService, null, null, null, null, null, (RunAssetContentStorage) null, null, null, DEFAULT_STUDY_UPLOAD_BYTES);
    }

    public AiBackendService(
        AiServiceOperations aiServiceClient,
        ReviewStoreService reviewStoreService,
        AuditService auditService,
        PipelineRunRequestNormalizer pipelineRunRequestNormalizer,
        SagittalRealBaselineContractValidator sagittalContractValidator,
        AiPipelineResponsePresenter pipelineResponsePresenter,
        AiModelReadinessResolver modelReadinessResolver
    ) {
        this(
            aiServiceClient,
            reviewStoreService,
            auditService,
            pipelineRunRequestNormalizer,
            sagittalContractValidator,
            pipelineResponsePresenter,
            modelReadinessResolver,
            null,
            (RunAssetContentStorage) null,
            null,
            null,
            DEFAULT_STUDY_UPLOAD_BYTES
        );
    }

    @Autowired
    public AiBackendService(
        AiServiceOperations aiServiceClient,
        ReviewStoreService reviewStoreService,
        AuditService auditService,
        PipelineRunRequestNormalizer pipelineRunRequestNormalizer,
        SagittalRealBaselineContractValidator sagittalContractValidator,
        AiPipelineResponsePresenter pipelineResponsePresenter,
        AiModelReadinessResolver modelReadinessResolver,
        StudyRepository studyRepository,
        ObjectProvider<RunAssetContentStorage> assetContentStorageProvider,
        ObjectProvider<RunAssetSnapshotService> runAssetSnapshotServiceProvider,
        ObjectProvider<RunReviewService> runReviewServiceProvider,
        AiServiceProperties aiServiceProperties
    ) {
        this(
            aiServiceClient,
            reviewStoreService,
            auditService,
            pipelineRunRequestNormalizer,
            sagittalContractValidator,
            pipelineResponsePresenter,
            modelReadinessResolver,
            studyRepository,
            assetContentStorageProvider.getIfAvailable(),
            runAssetSnapshotServiceProvider.getIfAvailable(),
            runReviewServiceProvider.getIfAvailable(),
            aiServiceProperties.resolvedStudyUploadMaxBytes()
        );
    }

    public AiBackendService(
        AiServiceOperations aiServiceClient,
        ReviewStoreService reviewStoreService,
        AuditService auditService,
        PipelineRunRequestNormalizer pipelineRunRequestNormalizer,
        SagittalRealBaselineContractValidator sagittalContractValidator,
        AiPipelineResponsePresenter pipelineResponsePresenter,
        AiModelReadinessResolver modelReadinessResolver,
        StudyRepository studyRepository,
        RunAssetContentStorage assetContentStorage,
        RunAssetSnapshotService runAssetSnapshotService,
        RunReviewService runReviewService
    ) {
        this(
            aiServiceClient,
            reviewStoreService,
            auditService,
            pipelineRunRequestNormalizer,
            sagittalContractValidator,
            pipelineResponsePresenter,
            modelReadinessResolver,
            studyRepository,
            assetContentStorage,
            runAssetSnapshotService,
            runReviewService,
            DEFAULT_STUDY_UPLOAD_BYTES
        );
    }

    public AiBackendService(
        AiServiceOperations aiServiceClient,
        ReviewStoreService reviewStoreService,
        AuditService auditService,
        PipelineRunRequestNormalizer pipelineRunRequestNormalizer,
        SagittalRealBaselineContractValidator sagittalContractValidator,
        AiPipelineResponsePresenter pipelineResponsePresenter,
        AiModelReadinessResolver modelReadinessResolver,
        StudyRepository studyRepository,
        RunAssetContentStorage assetContentStorage,
        RunAssetSnapshotService runAssetSnapshotService,
        RunReviewService runReviewService,
        long studyUploadMaxBytes
    ) {
        this.aiServiceClient = aiServiceClient;
        this.reviewStoreService = reviewStoreService;
        this.auditService = auditService;
        this.pipelineRunRequestNormalizer = pipelineRunRequestNormalizer;
        this.sagittalContractValidator = sagittalContractValidator;
        this.pipelineResponsePresenter = pipelineResponsePresenter;
        this.modelReadinessResolver = modelReadinessResolver;
        this.studyRepository = studyRepository;
        this.assetContentStorage = assetContentStorage;
        this.runAssetSnapshotService = runAssetSnapshotService;
        this.runReviewService = runReviewService;
        this.studyUploadMaxBytes = studyUploadMaxBytes <= 0 ? DEFAULT_STUDY_UPLOAD_BYTES : studyUploadMaxBytes;
    }

    public Map<String, Object> health() {
        try {
            Map<String, Object> response = normalizeForFrontend(aiServiceClient.health());
            response.put("status", response.getOrDefault("status", "ok"));
            response.put("backendStatus", "up");
            response.put("aiModuleAvailable", true);
            response.put("degradedMode", false);
            response.put("studyUploadMaxBytes", studyUploadMaxBytes);
            response.put("studyUploadAllowedContentTypes", allowedStudyContentTypes());
            return response;
        } catch (RuntimeException ex) {
            return Map.of(
                "status", "degraded",
                "backendStatus", "up",
                "aiModuleAvailable", false,
                "degradedMode", true,
                "studyUploadMaxBytes", studyUploadMaxBytes,
                "studyUploadAllowedContentTypes", allowedStudyContentTypes(),
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

    public StudyUploadResponseDto uploadStudy(MultipartFile file, String caseId) {
        String normalizedCaseId = trimmed(caseId);
        validateStudyUpload(file, normalizedCaseId);
        Map<String, Object> response = normalizeForFrontend(aiServiceClient.uploadStudy(file, normalizedCaseId));
        StudyUploadResponseDto publicResponse = toStudyUploadResponse(normalizedCaseId, response);
        String traceId = text(response.get("traceId"));
        audit("backend", "upload.study.completed", publicResponse.studyId(), traceId, Map.of(
            "caseId", publicResponse.caseId(),
            "studyId", publicResponse.studyId(),
            "traceId", traceId,
            "seriesCount", publicResponse.seriesFound().size(),
            "planesDetected", detectedPlanes(publicResponse),
            "hasSagittal", publicResponse.sagittal() != null,
            "hasAxial", publicResponse.axial() != null
        ));
        return publicResponse;
    }

    public ResponseEntity<byte[]> getAsset(String runId, String plane, String assetName) {
        String normalizedRunId = trimmed(runId);
        String normalizedPlane = normalized(plane);
        String normalizedAssetName = trimmed(assetName);
        validateAssetRequest(normalizedRunId, normalizedPlane, normalizedAssetName);
        Optional<RunArtifact> artifact = studyRepository == null
            ? Optional.empty()
            : studyRepository.findArtifactByRunPlaneAndName(normalizedRunId, normalizedPlane, normalizedAssetName);
        if (artifact.isPresent() && assetContentStorage != null) {
            Optional<RunAssetContent> stored = assetContentStorage.find(artifact.get().id());
            if (stored.isPresent()) {
                return durableAsset(stored.get(), artifact.get(), "postgres");
            }
            RunArtifact backfilled = runAssetSnapshotService == null ? artifact.get() : runAssetSnapshotService.backfill(artifact.get(), "");
            Optional<RunAssetContent> backfilledContent = assetContentStorage.find(backfilled.id());
            if (backfilledContent.isPresent()) {
                return durableAsset(backfilledContent.get(), backfilled, "ai-module-backfill");
            }
            throw new AssetContentUnavailableException(normalizedRunId, normalizedPlane, normalizedAssetName);
        }
        ResponseEntity<byte[]> upstream = aiServiceClient.getAsset(normalizedRunId, normalizedPlane, normalizedAssetName);
        HttpHeaders headers = new HttpHeaders();
        String contentType = upstream.getHeaders().getFirst(HttpHeaders.CONTENT_TYPE);
        if (contentType != null && !contentType.isBlank()) {
            headers.add(HttpHeaders.CONTENT_TYPE, contentType);
        }
        return new ResponseEntity<>(upstream.getBody(), headers, upstream.getStatusCode());
    }

    /**
     * Identifier of a stored series: exactly what the AI module mints in
     * {@code register_series_files}. Anchored, so nothing that could climb a path or
     * name another endpoint reaches the upstream URL.
     */
    private static final Pattern INPUT_ID_PATTERN = Pattern.compile("^inp_[0-9a-f]{32}$");

    /** Mismo techo que el catalogo de previsualizaciones del modulo de IA. */
    private static final int MAX_SERIES_SLICE_INDEX = 511;

    public ResponseEntity<byte[]> getSeriesSlice(String inputId, int index) {
        String normalizedInputId = trimmed(inputId);
        if (!INPUT_ID_PATTERN.matcher(normalizedInputId).matches()) {
            throw badRequest("inputId invalido.");
        }
        if (index < 0 || index > MAX_SERIES_SLICE_INDEX) {
            throw badRequest("Indice de corte fuera de rango.");
        }
        ResponseEntity<byte[]> upstream = aiServiceClient.getSeriesSlice(normalizedInputId, index);
        HttpHeaders headers = new HttpHeaders();
        String contentType = upstream.getHeaders().getFirst(HttpHeaders.CONTENT_TYPE);
        headers.add(HttpHeaders.CONTENT_TYPE, contentType == null || contentType.isBlank() ? MediaType.IMAGE_PNG_VALUE : contentType);
        // El corte de una serie no cambia nunca: el mismo inputId y el mismo indice
        // devuelven el mismo PNG. Sin cache el visor lo vuelve a pedir en cada paso
        // del cine, que a 200 ms por cuadro es la red haciendo de disco.
        headers.add(HttpHeaders.CACHE_CONTROL, "private, max-age=86400");
        return new ResponseEntity<>(upstream.getBody(), headers, upstream.getStatusCode());
    }

    /**
     * Identificador de corrida de plano: hexadecimal, como lo emite el modulo de IA.
     *
     * <p>Se valida antes de armar la URL upstream porque estos identificadores van a un
     * path, y un valor con barras o puntos podria hacer que la peticion salga a otra ruta
     * del modulo de IA.
     */
    private static final Pattern PLANE_RUN_ID_PATTERN = Pattern.compile("^[0-9a-zA-Z._-]{1,64}$");

    public ResponseEntity<byte[]> getRunSegmentation(String planeRunId, String plane) {
        return dicomExport(planeRunId, plane, aiServiceClient::getRunSegmentation, "segmentation.dcm");
    }

    public ResponseEntity<byte[]> getRunMeasurementReport(String planeRunId, String plane) {
        return dicomExport(planeRunId, plane, aiServiceClient::getRunMeasurementReport, "measurements.dcm");
    }

    /**
     * Descarga de un objeto DICOM generado por el modulo de IA.
     *
     * <p>Sin cache, a diferencia de los cortes: el objeto se construye en el momento y una
     * corrida revisada puede producir uno distinto. Servir una version vieja de un archivo
     * que alguien va a abrir en otro visor es peor que volver a generarlo.
     */
    private ResponseEntity<byte[]> dicomExport(
        String planeRunId,
        String plane,
        java.util.function.BiFunction<String, String, ResponseEntity<byte[]>> fetch,
        String filename
    ) {
        String normalizedRunId = trimmed(planeRunId);
        if (!PLANE_RUN_ID_PATTERN.matcher(normalizedRunId).matches()) {
            throw badRequest("runId invalido.");
        }
        String normalizedPlane = trimmed(plane).toLowerCase(java.util.Locale.ROOT);
        if (!normalizedPlane.equals("sagittal") && !normalizedPlane.equals("axial")) {
            throw badRequest("plane invalido.");
        }
        ResponseEntity<byte[]> upstream = fetch.apply(normalizedRunId, normalizedPlane);
        HttpHeaders headers = new HttpHeaders();
        headers.add(HttpHeaders.CONTENT_TYPE, "application/dicom");
        // El nombre lo arma el backend y no se copia del upstream: un Content-Disposition
        // que venga de afuera es una via para que un nombre de archivo elegido por otro
        // sistema llegue al disco del usuario.
        headers.add(HttpHeaders.CONTENT_DISPOSITION,
            "attachment; filename=\"" + normalizedRunId + "-" + normalizedPlane + "-" + filename + "\"");
        headers.add(HttpHeaders.CACHE_CONTROL, "no-store");
        return new ResponseEntity<>(upstream.getBody(), headers, upstream.getStatusCode());
    }

    private ResponseEntity<byte[]> durableAsset(RunAssetContent content, RunArtifact artifact, String source) {
        HttpHeaders headers = new HttpHeaders();
        headers.add(HttpHeaders.CONTENT_TYPE, artifact.contentType());
        headers.add(HttpHeaders.CONTENT_LENGTH, String.valueOf(content.sizeBytes()));
        headers.add(HttpHeaders.ETAG, "\"" + content.sha256() + "\"");
        headers.add(HttpHeaders.CACHE_CONTROL, "private, max-age=86400");
        headers.add("X-PFI-Asset-Source", source);
        return new ResponseEntity<>(content.content(), headers, HttpStatus.OK);
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
        if (runReviewService == null) {
            throw new DatabaseUnavailableException("Persistencia canonica de revision no disponible.");
        }
        RunReviewResponseDto response = runReviewService.saveReview(runId, new RunReviewRequestDto(
            ReviewStatusMapper.toDbStatus(normalizedRequest.status()),
            normalizedRequest.reviewer(),
            normalizedRequest.notes(),
            List.of()
        ));
        return new ReviewStatusDto(
            response.multiplanarRunId(),
            ReviewStatusMapper.toApiStatus(response.reviewStatus()),
            response.comments(),
            response.reviewer(),
            response.reviewedAt()
        );
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
            throw new MaxUploadSizeExceededException(MAX_INPUT_UPLOAD_BYTES);
        }
        String extension = inputExtension(file.getOriginalFilename());
        if (!ALLOWED_INPUT_EXTENSIONS.contains(extension)) {
            // La lista se deriva de ALLOWED_INPUT_EXTENSIONS en vez de repetirse: escrita
            // a mano quedaba desactualizada y el mensaje le mentia al medico sobre lo
            // que el sistema acepta.
            throw badRequest("Formato de input invalido. Extensiones permitidas: "
                    + ALLOWED_INPUT_EXTENSIONS.stream().sorted().map(value -> "." + value).collect(java.util.stream.Collectors.joining(",")) + ".");
        }
    }

    private void validateStudyUpload(MultipartFile file, String caseId) {
        if (file == null || file.isEmpty()) {
            throw badRequest("El archivo del estudio es obligatorio y no puede estar vacio.");
        }
        if (caseId.isBlank()) {
            throw badRequest("caseId es obligatorio.");
        }
        if (!CASE_ID_PATTERN.matcher(caseId).matches()) {
            throw badRequest("caseId invalido. Use solo letras, numeros, punto, guion, guion bajo o dos puntos; maximo 80 caracteres.");
        }
        if (file.getSize() > studyUploadMaxBytes) {
            throw new MaxUploadSizeExceededException(studyUploadMaxBytes);
        }
        if (!"zip".equals(inputExtension(file.getOriginalFilename()))) {
            throw badRequest("El estudio debe subirse como archivo .zip con la serie DICOM.");
        }
        String contentType = normalized(file.getContentType());
        if (!ALLOWED_STUDY_CONTENT_TYPES.contains(contentType)) {
            throw badRequest("Content-Type invalido para estudio ZIP. Use application/zip u application/octet-stream.");
        }
        if (!hasZipSignature(file)) {
            throw badRequest("El archivo del estudio no parece un ZIP valido.");
        }
    }

    private boolean hasZipSignature(MultipartFile file) {
        byte[] header = new byte[4];
        try (InputStream input = file.getInputStream()) {
            int read = input.read(header);
            return read == 4 && header[0] == 'P' && header[1] == 'K'
                && (header[2] == 3 || header[2] == 5 || header[2] == 7)
                && (header[3] == 4 || header[3] == 6 || header[3] == 8);
        } catch (IOException ex) {
            throw badRequest("No se pudo leer el archivo del estudio.");
        }
    }

    private StudyUploadResponseDto toStudyUploadResponse(String fallbackCaseId, Map<String, Object> response) {
        String caseId = firstText(response.get("caseId"), fallbackCaseId);
        String studyId = text(response.get("studyId"));
        StudyUploadInputDto sagittal = toStudyInput(response.get("sagittal"), "sagittal", response.get("seriesFound"));
        StudyUploadInputDto axial = toStudyInput(response.get("axial"), "axial", response.get("seriesFound"));
        StudyUploadInputDto sagittalT1 = toStudyInput(response.get("sagittalT1"), "sagittal", response.get("seriesFound"));
        StudyUploadInputDto sagittalT2 = toStudyInput(response.get("sagittalT2"), "sagittal", response.get("seriesFound"));
        List<StudyUploadSeriesDto> seriesFound = toStudySeriesList(response.get("seriesFound"));
        if (seriesFound.isEmpty()) {
            seriesFound = inferredSeries(sagittal, axial);
        }
        if (sagittal == null && axial == null) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "El modulo de IA no devolvio planos utilizables para el estudio.");
        }
        return new StudyUploadResponseDto(
            caseId,
            studyId,
            List.copyOf(seriesFound),
            sagittal,
            axial,
            sagittalT1,
            sagittalT2,
            toSafeWarnings(response.get("warnings")),
            true,
            true
        );
    }

    private StudyUploadInputDto toStudyInput(Object value, String expectedPlane, Object seriesFound) {
        Map<String, Object> map = objectMap(value);
        if (map.isEmpty()) return null;
        String plane = normalized(firstText(map.get("plane"), expectedPlane));
        if (!ALLOWED_INPUT_PLANES.contains(plane)) {
            return null;
        }
        String inputId = text(map.get("inputId"));
        if (inputId.isBlank()) {
            return null;
        }
        return new StudyUploadInputDto(
            inputId,
            seriesIndexOf(seriesFound, map.get("seriesInstanceUid")),
            plane,
            publicText(map.get("format")),
            numericLong(map.get("size")),
            publicText(map.get("description")),
            publicText(map.get("weighting")),
            numericInteger(map.get("sliceCount"))
        );
    }

    /**
     * Resuelve el UID de la serie a su posicion en la lista publicada.
     *
     * El emparejamiento se hace aca adentro, donde el UID todavia existe, y lo unico
     * que sale es un indice. Asi la pantalla puede decir cual de las series listadas
     * produjo los resultados sin que el identificador del estudio de origen salga del
     * backend.
     */
    private Integer seriesIndexOf(Object seriesFound, Object seriesInstanceUid) {
        String uid = text(seriesInstanceUid);
        if (uid.isBlank() || !(seriesFound instanceof List<?> list)) return null;
        for (int index = 0; index < list.size(); index += 1) {
            if (uid.equals(text(objectMap(list.get(index)).get("seriesInstanceUid")))) return index;
        }
        return null;
    }

    private List<StudyUploadSeriesDto> toStudySeriesList(Object value) {
        if (!(value instanceof List<?> list)) return List.of();
        List<StudyUploadSeriesDto> result = new ArrayList<>();
        for (Object item : list) {
            Map<String, Object> map = objectMap(item);
            /*
             * The listing keeps every plane, including the ones no model runs on.
             *
             * ALLOWED_INPUT_PLANES is the set a run can be launched against, and using
             * it to filter this listing conflated two different questions: a coronal
             * series and a localizer were dropped from the study's contents because
             * nothing could be inferred from them. The doctor still has to see that
             * the study carried them — a missing series they expected to find is
             * itself a finding, and silence here reads as "the study did not have it".
             */
            String plane = normalized(text(map.get("plane")));
            if (!VIEWABLE_PLANES.contains(plane)) continue;
            result.add(new StudyUploadSeriesDto(
                publicText(map.get("inputId")),
                plane,
                publicText(map.get("description")),
                publicText(map.get("weighting")),
                numericInteger(map.get("sliceCount")),
                booleanValue(map.get("multiplanar")),
                booleanValue(map.get("derived")),
                booleanValue(map.get("analyzable"))
            ));
        }
        return result;
    }

    private List<StudyUploadSeriesDto> inferredSeries(StudyUploadInputDto sagittal, StudyUploadInputDto axial) {
        List<StudyUploadSeriesDto> result = new ArrayList<>();
        // Reconstruido desde los planos elegidos: si se llega aca es porque el modulo
        // no publico la lista, asi que las dos series que si conocemos son analizables
        // por definicion -son las que se van a inferir- y no hay ninguna multiplano.
        if (sagittal != null) {
            result.add(new StudyUploadSeriesDto(
                sagittal.inputId(), sagittal.plane(), sagittal.description(),
                sagittal.weighting(), sagittal.sliceCount(), false, false, true));
        }
        if (axial != null) {
            result.add(new StudyUploadSeriesDto(
                axial.inputId(), axial.plane(), axial.description(),
                axial.weighting(), axial.sliceCount(), false, false, true));
        }
        return result;
    }

    private List<String> toSafeWarnings(Object value) {
        if (!(value instanceof List<?> list)) return List.of();
        return list.stream()
            .map(item -> SafeLogSanitizer.sanitizeMessage(text(item)))
            .filter(warning -> !warning.isBlank())
            .limit(10)
            .toList();
    }

    private List<String> detectedPlanes(StudyUploadResponseDto response) {
        List<String> planes = new ArrayList<>();
        if (response.sagittal() != null) planes.add("sagittal");
        if (response.axial() != null) planes.add("axial");
        return planes;
    }

    private Map<String, Object> objectMap(Object value) {
        if (!(value instanceof Map<?, ?> map)) return Map.of();
        @SuppressWarnings("unchecked")
        Map<String, Object> typed = (Map<String, Object>) map;
        return typed;
    }

    private long numericLong(Object value) {
        if (value instanceof Number number) return number.longValue();
        try {
            return value == null ? 0L : Long.parseLong(value.toString());
        } catch (NumberFormatException ex) {
            return 0L;
        }
    }

    private Integer numericInteger(Object value) {
        if (value instanceof Number number) return number.intValue();
        try {
            return value == null ? null : Integer.parseInt(value.toString());
        } catch (NumberFormatException ex) {
            return null;
        }
    }

    private boolean booleanValue(Object value) {
        if (value instanceof Boolean flag) return flag;
        return value != null && Boolean.parseBoolean(value.toString());
    }

    private String firstText(Object value, String fallback) {
        String text = text(value);
        return text.isBlank() ? fallback : text;
    }

    private String publicText(Object value) {
        String text = text(value);
        if (text.isBlank()) return "";
        String redacted = SafeLogSanitizer.redactValue(text);
        return redacted == null ? "" : redacted;
    }

    private List<String> allowedStudyContentTypes() {
        return ALLOWED_STUDY_CONTENT_TYPES.stream().filter(value -> !value.isBlank()).sorted().toList();
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
        if (!ALLOWED_INPUT_PLANES.contains(plane) && !"workspace".equals(plane)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Asset no encontrado.");
        }
        if (!isSimpleBasename(assetName) || !(ALLOWED_ASSET_NAMES.contains(assetName) || isSlicePreviewName(assetName) || isClassMaskName(assetName))) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Asset no permitido.");
        }
        if ("workspace".equals(plane) && !"lumbar-3d-mesh.json".equals(assetName)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Asset no permitido.");
        }
        if (!"workspace".equals(plane) && "lumbar-3d-mesh.json".equals(assetName)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Asset no permitido.");
        }
    }

    /**
     * Per-slice asset of a series: the preview ({@code slice-007.png}) or its raw
     * 16-bit pixels ({@code slice-007.raw}). It is the only asset
     * name that cannot live in a fixed list, because how many slices a study has
     * depends on the study. The pattern is as strict as the list — digits only, no
     * path separators, fixed extension — so it does not widen the traversal surface
     * the list already closed. The workspace plane is still restricted to the mesh
     * by the checks right below this one.
     */
    private boolean isSlicePreviewName(String assetName) {
        return SLICE_PREVIEW_NAME.matcher(assetName).matches();
    }

    /**
     * Per-class segmentation mask ({@code mask-vertebra_group.png}). Same reasoning as
     * the slice preview: the set depends on the model, so it cannot be a fixed list,
     * and the pattern stays as strict as the list it complements.
     */
    private boolean isClassMaskName(String assetName) {
        return CLASS_MASK_NAME.matcher(assetName).matches();
    }

    private static final java.util.regex.Pattern CLASS_MASK_NAME =
            java.util.regex.Pattern.compile("^mask-[a-z][a-z0-9_]{0,31}\\.png$");

    private static final java.util.regex.Pattern SLICE_PREVIEW_NAME =
            java.util.regex.Pattern.compile("^slice-\\d{3,5}\\.(png|raw)$");

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
