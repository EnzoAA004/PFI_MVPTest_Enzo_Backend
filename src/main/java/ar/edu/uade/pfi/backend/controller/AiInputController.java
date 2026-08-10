package ar.edu.uade.pfi.backend.controller;

import ar.edu.uade.pfi.backend.dto.AiInputResponseDto;
import ar.edu.uade.pfi.backend.dto.StudyUploadResponseDto;
import ar.edu.uade.pfi.backend.service.AiBackendService;
import ar.edu.uade.pfi.backend.web.error.ApiErrorResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/** Ingests individual series and complete DICOM studies. */
@RestController
@RequestMapping("/api/ai")
@Tag(
    name = "IA - corridas y assets",
    description = "Ingesta de series, ejecucion de inferencia y acceso a los artefactos.")
public class AiInputController {
  private final AiBackendService aiBackendService;

  public AiInputController(AiBackendService aiBackendService) {
    this.aiBackendService = aiBackendService;
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
}
