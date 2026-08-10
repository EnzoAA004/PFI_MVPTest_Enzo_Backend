package ar.edu.uade.pfi.backend.controller;

import ar.edu.uade.pfi.backend.service.AiBackendService;
import ar.edu.uade.pfi.backend.web.error.ApiErrorResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Serves viewable assets, series slices and interoperable DICOM exports. */
@RestController
@RequestMapping("/api/ai")
@Tag(
    name = "IA - corridas y assets",
    description = "Ingesta de series, ejecucion de inferencia y acceso a los artefactos.")
public class AiAssetController {
  private final AiBackendService aiBackendService;

  public AiAssetController(AiBackendService aiBackendService) {
    this.aiBackendService = aiBackendService;
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

  /** DICOM SEG export for PACS, OHIF and 3D Slicer interoperability. */
  @GetMapping("/runs/{planeRunId}/{plane}/segmentation.dcm")
  public ResponseEntity<byte[]> getRunSegmentation(
      @PathVariable String planeRunId, @PathVariable String plane) {
    return aiBackendService.getRunSegmentation(planeRunId, plane);
  }

  /** DICOM SR measurement report; counterpart of the SEG export. */
  @GetMapping("/runs/{planeRunId}/{plane}/measurements.sr.dcm")
  public ResponseEntity<byte[]> getRunMeasurementReport(
      @PathVariable String planeRunId, @PathVariable String plane) {
    return aiBackendService.getRunMeasurementReport(planeRunId, plane);
  }
}
