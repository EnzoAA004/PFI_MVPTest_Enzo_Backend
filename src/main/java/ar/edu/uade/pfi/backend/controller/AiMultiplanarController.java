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
import ar.edu.uade.pfi.backend.web.error.ApiErrorResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
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
@Tag(
    name = "IA - corrida multiplanar",
    description = "Analisis dual sagital/axial, el flujo principal del sistema.")
public class AiMultiplanarController {
  private final AiServiceOperations aiServiceClient;
  private final MultiplanarRunPersistenceService persistenceService;
  private final AuditService auditService;
  private final MultiplanarRunResponsePresenter presenter;
  private final CanonicalMultiplanarRunLegacyPresenter legacyPresenter;
  private final MultiplanarRealBaselineContractValidator strictnessClassifier;

  public AiMultiplanarController(AiServiceOperations aiServiceClient) {
    this(
        aiServiceClient,
        null,
        null,
        new MultiplanarRunResponsePresenter(),
        new CanonicalMultiplanarRunLegacyPresenter(),
        new MultiplanarRealBaselineContractValidator());
  }

  public AiMultiplanarController(
      AiServiceOperations aiServiceClient, MultiplanarRunPersistenceService persistenceService) {
    this(
        aiServiceClient,
        persistenceService,
        null,
        new MultiplanarRunResponsePresenter(),
        new CanonicalMultiplanarRunLegacyPresenter(),
        new MultiplanarRealBaselineContractValidator());
  }

  @Autowired
  public AiMultiplanarController(
      AiServiceOperations aiServiceClient,
      MultiplanarRunPersistenceService persistenceService,
      AuditService auditService) {
    this(
        aiServiceClient,
        persistenceService,
        auditService,
        new MultiplanarRunResponsePresenter(),
        new CanonicalMultiplanarRunLegacyPresenter(),
        new MultiplanarRealBaselineContractValidator());
  }

  AiMultiplanarController(
      AiServiceOperations aiServiceClient,
      MultiplanarRunPersistenceService persistenceService,
      AuditService auditService,
      MultiplanarRunResponsePresenter presenter,
      CanonicalMultiplanarRunLegacyPresenter legacyPresenter,
      MultiplanarRealBaselineContractValidator strictnessClassifier) {
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
      return fallback();
    }
  }

  @Operation(
      summary = "Ejecuta una corrida dual sagital + axial",
      description =
          """
          Es el endpoint principal del flujo clinico de la tesis: corre los dos planos en una
          sola operacion y devuelve la corrida canonica con mascaras, landmarks, mediciones y
          metadata volumetrica por plano.

          Flujo recomendado:

          1. `POST /api/ai/inputs` para cada plano, o `POST /api/ai/studies` con el estudio
             completo.
          2. `POST /api/ai/multiplanar/run` con `sagittalInputId`, `axialInputId`,
             `sagittalModelKey=sagittal_spider`, `axialModelKey=axial_t2_alkafri` y
             `metadata.inferenceMode=real_baseline` con `allowContractFallback=false`.
          3. Leer `planes.sagittal.effectiveInferenceMode` y `planes.axial.effectiveInferenceMode`
             antes de mostrar nada: son los que dicen si el resultado es real o degradado.
          4. Abrir los assets solo por las URLs proxy del backend.
          5. Registrar la revision con `PATCH /api/ai/review/{runId}`.

          En modo estricto el sagital debe coincidir con el checkpoint final y su SHA-256
          esperado; el axial se valida como real de forma independiente. Si una corrida
          estricta queda mixed, contract, fallback o degradada, el backend devuelve
          `AI_MULTIPLANAR_CONTRACT_VIOLATION`, **no persiste una corrida completada falsa** y
          audita el fallo.

          La respuesta publica elimina toda ruta interna (`inputPath`, `sourcePath`,
          `outputFiles`, rutas de Colab o Drive) y publica solo `input.png`, `overlay.png` y
          `mask-preview.png` como URLs relativas. `mask.npy` y `confidence.npy` no llegan al
          navegador.
          """)
  @ApiResponse(
      responseCode = "200",
      description =
          """
          Corrida procesada. Requiere revision profesional.

          **Un 200 no garantiza inferencia real.** Fuera del modo estricto, si el modulo de \
          IA no esta disponible la respuesta sigue siendo 200 con \
          `effectiveInferenceMode: "contract"`, que es un resultado de contrato y no una \
          corrida del modelo. Hay que mirar `effectiveInferenceMode` por plano antes de \
          presentar nada como resultado del sistema.\
          """)
  @ApiResponse(
      responseCode = "400",
      description = "`BAD_REQUEST`: no se pidio ningun plano, o el payload no es coherente.",
      content = @Content(schema = @Schema(implementation = ApiErrorResponse.class)))
  @ApiResponse(
      responseCode = "502",
      description =
          "`AI_MULTIPLANAR_CONTRACT_VIOLATION`: solo en modo estricto. La respuesta del modulo"
              + " de IA no cumple el contrato, o la corrida quedo mixed/fallback/degradada. No"
              + " se persiste una corrida completada falsa y el fallo queda auditado.",
      content = @Content(schema = @Schema(implementation = ApiErrorResponse.class)))
  @ApiResponse(
      responseCode = "503",
      description = "`UPSTREAM_UNAVAILABLE`: solo en modo estricto. El modulo de IA no respondio.",
      content = @Content(schema = @Schema(implementation = ApiErrorResponse.class)))
  @ApiResponse(
      responseCode = "504",
      description = "`AI_TIMEOUT`: el modulo de IA excedio `PFI_AI_TIMEOUT_SECONDS`.",
      content = @Content(schema = @Schema(implementation = ApiErrorResponse.class)))
  @PostMapping("/run")
  public MultiplanarRunResponseDto run(@Valid @RequestBody MultiplanarRunApiRequestDto request) {
    PreparedStudyMetadata preparedMetadata =
        persistenceService == null
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
    MultiplanarRunResponseDto presented =
        presenter.present(legacyPresenter.toLegacyResponse(canonical));
    if (persistenceService != null) {
      persistenceService.persistSuccessfulRun(normalized, preparedMetadata.metadata(), canonical);
    }
    auditSuccess(normalized, canonical);
    return presented;
  }

  private MultiplanarRunRequestDto normalizedRequest(
      MultiplanarRunApiRequestDto request, PreparedStudyMetadata preparedMetadata) {
    String caseId = preparedMetadata == null ? request.caseId().trim() : preparedMetadata.caseId();
    String sagittalModel = valueOrDefault(request.sagittalModelKey(), "sagittal_spider");
    String axialModel = valueOrDefault(request.axialModelKey(), "axial_t2_alkafri");
    Map<String, Object> metadata = new LinkedHashMap<>();
    if (request.metadata() != null) metadata.putAll(request.metadata());
    if (request.allowContractFallback() != null)
      metadata.put("allowContractFallback", request.allowContractFallback());
    metadata.putIfAbsent("source", "backend-multiplanar-run");
    boolean realBaseline =
        "real_baseline".equals(String.valueOf(metadata.getOrDefault("inferenceMode", "")).trim());
    boolean strict =
        realBaseline
            && (Boolean.FALSE.equals(request.allowContractFallback())
                || Boolean.FALSE.equals(metadata.get("allowContractFallback")));
    if (strict) {
      validateStrictInputs(request);
      sagittalModel = "sagittal_spider";
      axialModel = "axial_t2_alkafri";
      boolean axialPresent =
          valueOrNull(request.axialInputId()) != null
              || valueOrNull(request.axialInputPath()) != null;
      metadata.put("inferenceMode", "real_baseline");
      metadata.put("requestedInferenceMode", "real_baseline");
      metadata.put("allowContractFallback", false);
      metadata.putIfAbsent(
          "axialMode", axialPresent ? "optional_provided" : "optional_not_provided");
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
        request.studySeries(),
        metadata);
  }

  private void validateStrictInputs(MultiplanarRunApiRequestDto request) {
    boolean sagittalInputIdPresent = valueOrNull(request.sagittalInputId()) != null;
    boolean sagittalInputPathPresent = valueOrNull(request.sagittalInputPath()) != null;
    boolean axialInputIdPresent = valueOrNull(request.axialInputId()) != null;
    boolean axialInputPathPresent = valueOrNull(request.axialInputPath()) != null;

    requireStrict(
        sagittalInputIdPresent || sagittalInputPathPresent,
        "sagittalInputId o sagittalInputPath es obligatorio para real_baseline estricto.");
    requireStrict(
        !(sagittalInputIdPresent && sagittalInputPathPresent),
        "Enviar solamente sagittalInputId o sagittalInputPath, no ambos.");
    requireStrict(
        !(axialInputIdPresent && axialInputPathPresent),
        "Enviar solamente axialInputId o axialInputPath, no ambos.");
    requireStrict(
        !startsDemo(request.sagittalInputId()) && !startsDemo(request.sagittalInputPath()),
        "Inputs demo no permitidos para sagital real_baseline estricto.");
    if (axialInputIdPresent || axialInputPathPresent) {
      requireStrict(
          !startsDemo(request.axialInputId()) && !startsDemo(request.axialInputPath()),
          "Inputs demo no permitidos para axial real_baseline estricto.");
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
    String action =
        strictnessClassifier.isStrict(request)
            ? "multiplanar.real_baseline.completed"
            : "multiplanar.run.completed";
    auditService.record(
        "backend",
        action,
        response.multiplanarRunId(),
        response.traceId(),
        auditMetadata(request, response, sagittal, axial));
  }

  private void auditStrictFailure(MultiplanarRunRequestDto request, String message) {
    if (auditService == null || !strictnessClassifier.isStrict(request)) return;
    auditService.record(
        "backend",
        ar.edu.uade.pfi.backend.service.AuditAction.AI_RUN_FAILED.name(),
        request.caseId(),
        traceId(request),
        Map.of(
            "caseId",
            request.caseId(),
            "traceId",
            traceId(request),
            "sagittalInputIdPresent",
            request.sagittalInputId() != null,
            "axialInputIdPresent",
            request.axialInputId() != null,
            "message",
            message == null ? "strict multiplanar failed" : message));
  }

  private Map<String, Object> auditMetadata(
      MultiplanarRunRequestDto request,
      CanonicalMultiplanarRun response,
      CanonicalPlaneRun sagittal,
      CanonicalPlaneRun axial) {
    Map<String, Object> metadata = new LinkedHashMap<>();
    metadata.put("runId", response.multiplanarRunId());
    metadata.put("sagittalRunId", sagittal == null ? "" : sagittal.planeRunId());
    metadata.put("axialRunId", axial == null ? "" : axial.planeRunId());
    metadata.put("caseId", request.caseId());
    metadata.put(
        "sagittalModelKey",
        sagittal == null ? request.sagittalModelKey() : modelKey(sagittal.model()));
    metadata.put("sagittalModelVersion", sagittal == null ? "" : modelVersion(sagittal.model()));
    metadata.put(
        "sagittalArtifactHash",
        sagittal == null ? "" : String.valueOf(sagittal.model().get("artifactHash")));
    metadata.put(
        "sagittalInferenceMode", sagittal == null ? "" : sagittal.effectiveInferenceMode());
    metadata.put(
        "axialModelKey", axial == null ? request.axialModelKey() : modelKey(axial.model()));
    metadata.put("axialInferenceMode", axial == null ? "" : axial.effectiveInferenceMode());
    metadata.put("sagittalInputIdPresent", request.sagittalInputId() != null);
    metadata.put("axialInputIdPresent", request.axialInputId() != null);
    metadata.put(
        "axialInferenceRequested",
        request.axialInputId() != null || request.axialInputPath() != null);
    metadata.put(
        "dualRunReady", axial != null && "real_baseline".equals(axial.effectiveInferenceMode()));
    metadata.put("traceId", response.traceId());
    metadata.put("humanReviewRequired", response.governance().humanReviewRequired());
    return metadata;
  }

  /**
   * Model key/version live under different map keys depending on the upstream contract: v2 stores
   * the wire names "key"/"version" as-is, v1 stores "modelKey"/"modelVersion".
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

  private Map<String, Object> fallback() {
    return Map.of(
        "status",
        "multiplanar_unavailable",
        "schemaVersion",
        "multiplanar-workspace-v1",
        "workspaceMode",
        "dual_plane_with_3d_context",
        "panels",
        List.of("sagittal", "axial", "three_d"),
        "message",
        "AI Module no disponible.",
        "proxiedByBackend",
        true,
        "degradedMode",
        true,
        "humanReviewRequired",
        true,
        "notClinicalDiagnosis",
        true);
  }
}
