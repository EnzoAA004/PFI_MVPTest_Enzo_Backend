package ar.edu.uade.pfi.backend.controller;

import ar.edu.uade.pfi.backend.auth.RoleAuthorizationService;
import ar.edu.uade.pfi.backend.config.error.ApiErrorResponse;
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
import ar.edu.uade.pfi.backend.dto.StudyUploadResponseDto;
import ar.edu.uade.pfi.backend.service.AiBackendService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/api/ai")
@Tag(
    name = "IA - corridas y assets",
    description = "Ingesta de series, ejecucion de inferencia y acceso a los artefactos.")
public class AiBackendController {
  private final AiBackendService aiBackendService;
  private final RoleAuthorizationService authorizationService;

  @Autowired
  public AiBackendController(
      AiBackendService aiBackendService, RoleAuthorizationService authorizationService) {
    this.aiBackendService = aiBackendService;
    this.authorizationService = authorizationService;
  }

  /**
   * No longer publicly reachable (see AuthFilter.PUBLIC_LIVENESS_PATHS): any authenticated,
   * non-pending user (professional or ADMIN) may call this — it is gated purely by AuthFilter's
   * default "authenticated" requirement, no extra role check needed here.
   */
  @GetMapping("/health")
  public Map<String, Object> health() {
    return aiBackendService.health();
  }

  /**
   * Technical diagnostic with no documented professional consumer today — ADMIN-only per P10-A.1
   * (was previously reachable by any authenticated professional).
   */
  @GetMapping("/readiness")
  public Map<String, Object> readiness(HttpServletRequest request) {
    authorizationService.requireAdmin(request, "ai.readiness");
    return aiBackendService.readiness();
  }

  /** Same as /health: gated purely by AuthFilter's default "authenticated" requirement. */
  @GetMapping("/models")
  public Object models() {
    return aiBackendService.models();
  }

  /**
   * Technical diagnostic with no documented professional consumer today — ADMIN-only per P10-A.1
   * (was previously reachable by any authenticated professional).
   */
  @GetMapping("/models/verify")
  public Map<String, Object> verifyModels(HttpServletRequest request) {
    authorizationService.requireAdmin(request, "ai.models.verify");
    return aiBackendService.verifyModels();
  }

  @Operation(
      summary = "Ejecuta el pipeline de un plano y devuelve el resultado revisable",
      description =
          """
          Corre la inferencia de un unico plano sobre un `inputId` ya subido y devuelve
          mascaras, landmarks y mediciones, siempre con `humanReviewRequired=true`.

          El flujo recomendado es subir primero el archivo con `POST /api/ai/inputs` y usar
          el `inputId` opaco que devuelve. Se acepta `inputId` o `inputPath`, nunca los dos.

          **Modo estricto**: cuando `metadata.inferenceMode=real_baseline` y
          `metadata.allowContractFallback=false`, se exige `plane=sagittal`,
          `modelKey=sagittal_spider` y un `inputId`/`inputPath`. Si falta
          `allowContractFallback` en una request `real_baseline`, el backend agrega `false`.
          En modo estricto no se genera resultado demo ni `degraded-*`: un fallo del modulo
          de IA es un error, no un resultado degradado.

          La respuesta no incluye rutas internas del modulo de IA: los assets se piden por
          `GET /api/ai/assets/{runId}/{plane}/{assetName}`.

          ## Un 200 no siempre es una corrida real

          Esto es lo mas importante de este endpoint y conviene no descubrirlo en produccion.

          **Fuera del modo estricto**, si el modulo de IA falla o no esta disponible, la
          respuesta **sigue siendo 200**, con `degradedMode=true`, `aiModuleAvailable=false`,
          un `runId` con prefijo `degraded-` y el motivo en `message`. Es un resultado de
          relleno para que la interfaz no se rompa, **no** una inferencia.

          Un cliente **tiene que mirar `degradedMode`** antes de mostrar nada como resultado
          del modelo. Confiar en el codigo HTTP alcanza para saber que la request se proceso,
          no para saber que hubo inferencia.

          **En modo estricto** no existe ese relleno: el mismo fallo devuelve 502 y no se
          persiste una corrida completada falsa.
          """)
  @ApiResponse(
      responseCode = "200",
      description =
          "Corrida procesada. Requiere revision profesional. Verificar `degradedMode`: en modo"
              + " no estricto un 200 puede ser un resultado de relleno.")
  @ApiResponse(
      responseCode = "400",
      description =
          "`BAD_REQUEST`: el payload no supera la validacion (campos faltantes o mal"
              + " formados).",
      content = @Content(schema = @Schema(implementation = ApiErrorResponse.class)))
  @ApiResponse(
      responseCode = "502",
      description =
          "`AI_CONTRACT_VIOLATION`: solo en modo estricto. El modulo de IA respondio algo que"
              + " no cumple el contrato, o la corrida quedo degradada. No se persiste una"
              + " corrida completada falsa.",
      content = @Content(schema = @Schema(implementation = ApiErrorResponse.class)))
  @ApiResponse(
      responseCode = "504",
      description =
          "`AI_TIMEOUT`: el modulo de IA no respondio dentro de `PFI_AI_TIMEOUT_SECONDS`.",
      content = @Content(schema = @Schema(implementation = ApiErrorResponse.class)))
  @PostMapping("/pipeline/run")
  public Map<String, Object> runPipeline(@Valid @RequestBody PipelineRunRequestDto request) {
    return aiBackendService.runPipeline(request);
  }

  @Operation(
      summary = "Sube una serie y devuelve un inputId opaco",
      description =
          """
          Sube un archivo de imagen medica (`.mha`, `.mhd`, `.npy`, o un DICOM) al modulo de
          IA y devuelve un `inputId` opaco para usar en `POST /api/ai/pipeline/run` o
          `POST /api/ai/multiplanar/run`.

          El identificador es opaco a proposito: la ruta real dentro del modulo de IA no se
          publica nunca. El ciclo de vida del archivo pertenece al modulo de IA.

          El techo de tamano lo fija `PFI_MAX_UPLOAD_FILE_SIZE` (200 MB por defecto).
          """)
  @ApiResponse(responseCode = "200", description = "Archivo aceptado; devuelve el `inputId`.")
  @ApiResponse(
      responseCode = "400",
      description = "Plano invalido, archivo vacio o formato no soportado.",
      content = @Content(schema = @Schema(implementation = ApiErrorResponse.class)))
  @ApiResponse(
      responseCode = "413",
      description = "`INPUT_TOO_LARGE`: el archivo supera el maximo permitido.",
      content = @Content(schema = @Schema(implementation = ApiErrorResponse.class)))
  @PostMapping(value = "/inputs", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
  public AiInputResponseDto uploadInput(
      @Parameter(description = "Archivo de la serie.") @RequestParam("file") MultipartFile file,
      @Parameter(
              description = "Identificador del caso, elegido por el profesional.",
              example = "case-001")
          @RequestParam
          String caseId,
      @Parameter(description = "Plano de la serie.", example = "sagittal") @RequestParam
          String plane) {
    return aiBackendService.uploadInput(file, caseId, plane);
  }

  @Operation(
      summary = "Sube un estudio DICOM completo en .zip",
      description =
          """
          Sube un estudio entero comprimido. El modulo de IA lo de-identifica, clasifica sus
          series por plano y devuelve `studyId`, las series publicas y un `inputId` opaco por
          plano, listos para una corrida multiplanar.

          La respuesta no expone nombres de archivo ni rutas internas. El techo lo fija
          `PFI_AI_STUDY_UPLOAD_MAX_BYTES` (200 MB por defecto); el modulo de IA aplica
          ademas limites propios de cantidad de archivos y de tamano descomprimido.
          """)
  @ApiResponse(
      responseCode = "200",
      description = "Estudio ingerido; devuelve `studyId`, series e `inputId` por plano.")
  @ApiResponse(
      responseCode = "400",
      description = "El zip no es un estudio valido o no contiene series utilizables.",
      content = @Content(schema = @Schema(implementation = ApiErrorResponse.class)))
  @ApiResponse(
      responseCode = "413",
      description = "`INPUT_TOO_LARGE`: el zip supera el maximo permitido.",
      content = @Content(schema = @Schema(implementation = ApiErrorResponse.class)))
  @PostMapping(value = "/studies", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
  public StudyUploadResponseDto uploadStudy(
      @Parameter(description = "Estudio DICOM completo comprimido en .zip.") @RequestParam("file")
          MultipartFile file,
      @Parameter(description = "Identificador del caso.", example = "case-001") @RequestParam
          String caseId) {
    return aiBackendService.uploadStudy(file, caseId);
  }

  @Operation(
      summary = "Proxy unico de los assets de una corrida",
      description =
          """
          Devuelve una imagen generada por una corrida. Es el unico camino por el que el
          navegador accede a los artefactos: el modulo de IA no se expone, y por eso las URLs
          que publica el backend son siempre relativas a este endpoint.

          Assets publicos: `input.png`, `overlay.png`, `mask-preview.png`.

          `mask.npy` y `confidence.npy` **no** se publican: son datos de inferencia crudos y
          el backend los rechaza con 403 aunque existan en el modulo de IA.
          """)
  @ApiResponse(responseCode = "200", description = "Contenido binario del asset.")
  @ApiResponse(
      responseCode = "403",
      description = "`ACCESS_DENIED`: asset no publicable (`mask.npy`, `confidence.npy`).",
      content = @Content(schema = @Schema(implementation = ApiErrorResponse.class)))
  @ApiResponse(
      responseCode = "404",
      description = "`ASSET_CONTENT_UNAVAILABLE`: la corrida o el asset no existen.",
      content = @Content(schema = @Schema(implementation = ApiErrorResponse.class)))
  @GetMapping("/assets/{runId}/{plane}/{assetName}")
  public ResponseEntity<byte[]> getAsset(
      @Parameter(description = "Id de la corrida del plano.") @PathVariable String runId,
      @Parameter(description = "Plano.", example = "sagittal") @PathVariable String plane,
      @Parameter(description = "Nombre del asset.", example = "overlay.png") @PathVariable
          String assetName) {
    return aiBackendService.getAsset(runId, plane, assetName);
  }

  @GetMapping("/series/{inputId}/slices/{index}")
  public ResponseEntity<byte[]> getSeriesSlice(
      @PathVariable String inputId, @PathVariable int index) {
    return aiBackendService.getSeriesSlice(inputId, index);
  }

  /**
   * La segmentacion de una corrida en formato DICOM SEG.
   *
   * <p>Es lo que permite abrir el resultado en 3D Slicer, OHIF o un PACS de hospital sin este
   * software en el medio. Hasta ahora la exportacion era csv, html o json: formatos que solo
   * entiende este producto.
   */
  @GetMapping("/runs/{planeRunId}/{plane}/segmentation.dcm")
  public ResponseEntity<byte[]> getRunSegmentation(
      @PathVariable String planeRunId, @PathVariable String plane) {
    return aiBackendService.getRunSegmentation(planeRunId, plane);
  }

  /** Las mediciones de una corrida en formato DICOM SR. Contraparte del SEG. */
  @GetMapping("/runs/{planeRunId}/{plane}/measurements.sr.dcm")
  public ResponseEntity<byte[]> getRunMeasurementReport(
      @PathVariable String planeRunId, @PathVariable String plane) {
    return aiBackendService.getRunMeasurementReport(planeRunId, plane);
  }

  @GetMapping("/agent/reports")
  public Map<String, Object> getRecentAgentReports(@RequestParam(defaultValue = "20") int limit) {
    return aiBackendService.getRecentAgentReports(limit);
  }

  @GetMapping("/agent/report/{runId}/summary")
  public Map<String, Object> getAgentReportSummary(@PathVariable String runId) {
    return aiBackendService.getAgentReportSummary(runId);
  }

  @GetMapping("/agent/report/{runId}")
  public Map<String, Object> getAgentReport(@PathVariable String runId) {
    return aiBackendService.getAgentReport(runId);
  }

  @Operation(
      summary = "Registra la decision profesional sobre una corrida",
      description =
          """
          Guarda la revision humana de una corrida. Es el paso que cierra el circuito: el
          sistema propone y el profesional acepta, observa o descarta. Ninguna salida del
          modulo de IA se considera validada hasta que pasa por aca.

          Estados admitidos: `aceptado`, `observado`, `descartado`, `pendiente`.
          `observado` y `descartado` exigen `notes` no vacio, porque una observacion sin
          motivo no es revisable despues.
          """)
  @ApiResponse(responseCode = "200", description = "Revision registrada y auditada.")
  @ApiResponse(
      responseCode = "400",
      description =
          "`BAD_REQUEST`: estado fuera del conjunto admitido, falta `reviewer`, o falta"
              + " `notes` en un `observado`/`descartado`.",
      content = @Content(schema = @Schema(implementation = ApiErrorResponse.class)))
  @PatchMapping("/review/{runId}")
  public ReviewStatusDto updateReview(
      @Parameter(description = "Id de la corrida revisada.") @PathVariable String runId,
      @Valid @RequestBody ReviewUpdateRequestDto request) {
    return aiBackendService.updateReview(runId, request);
  }

  @GetMapping("/review/history")
  public ReviewSnapshotDto reviewHistory() {
    return aiBackendService.reviewHistory();
  }

  @GetMapping("/review/{runId}/measurements")
  public List<MeasurementSaveDto> getMeasurements(@PathVariable String runId) {
    return aiBackendService.getMeasurements(runId);
  }

  @Operation(
      summary = "Guarda las mediciones corregidas por el profesional",
      description =
          """
          Reemplaza el conjunto de mediciones editadas de una corrida. Las mediciones que
          propuso el modelo no se sobrescriben: quedan como valor original y esto guarda la
          correccion al lado, para que despues se pueda comparar lo que dijo el sistema
          contra lo que corrigio la persona.

          Es un reemplazo completo del lote, no un merge.
          """)
  @ApiResponse(responseCode = "200", description = "Mediciones guardadas; devuelve el lote.")
  @ApiResponse(
      responseCode = "400",
      description = "`BAD_REQUEST`: lote invalido, falta el reviewer o una medicion mal formada.",
      content = @Content(schema = @Schema(implementation = ApiErrorResponse.class)))
  @PutMapping("/review/{runId}/measurements")
  public List<MeasurementSaveDto> saveMeasurements(
      @Parameter(description = "Id de la corrida.") @PathVariable String runId,
      @RequestBody MeasurementBatchDto request) {
    return aiBackendService.saveMeasurements(runId, request);
  }

  @PostMapping("/review/{runId}/export")
  public ReviewExportResponseDto exportReview(
      @PathVariable String runId, @RequestBody ReviewExportRequestDto request) {
    return aiBackendService.exportReview(runId, request);
  }

  @PostMapping("/audit")
  public AuditEventDto appendAudit(
      @RequestBody AuditEventRequestDto request, HttpServletRequest httpRequest) {
    authorizationService.requireAdmin(httpRequest, "audit.trail");
    return aiBackendService.appendAudit(request);
  }

  @GetMapping("/audit")
  public List<AuditEventDto> auditTrail(HttpServletRequest httpRequest) {
    authorizationService.requireAdmin(httpRequest, "audit.trail");
    return aiBackendService.auditTrail();
  }
}
