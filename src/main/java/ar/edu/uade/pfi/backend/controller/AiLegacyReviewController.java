package ar.edu.uade.pfi.backend.controller;

import ar.edu.uade.pfi.backend.dto.MeasurementBatchDto;
import ar.edu.uade.pfi.backend.dto.MeasurementSaveDto;
import ar.edu.uade.pfi.backend.dto.ReviewExportRequestDto;
import ar.edu.uade.pfi.backend.dto.ReviewExportResponseDto;
import ar.edu.uade.pfi.backend.dto.ReviewSnapshotDto;
import ar.edu.uade.pfi.backend.dto.ReviewStatusDto;
import ar.edu.uade.pfi.backend.dto.ReviewUpdateRequestDto;
import ar.edu.uade.pfi.backend.service.AiBackendService;
import ar.edu.uade.pfi.backend.web.error.ApiErrorResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Legacy review contract retained unchanged while canonical run review lives separately. */
@RestController
@RequestMapping("/api/ai/review")
@Tag(
    name = "IA - corridas y assets",
    description = "Ingesta de series, ejecucion de inferencia y acceso a los artefactos.")
public class AiLegacyReviewController {
  private final AiBackendService aiBackendService;

  public AiLegacyReviewController(AiBackendService aiBackendService) {
    this.aiBackendService = aiBackendService;
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
  @PatchMapping("/{runId}")
  public ReviewStatusDto updateReview(
      @Parameter(description = "Id de la corrida revisada.") @PathVariable String runId,
      @Valid @RequestBody ReviewUpdateRequestDto request) {
    return aiBackendService.updateReview(runId, request);
  }

  @GetMapping("/history")
  public ReviewSnapshotDto reviewHistory() {
    return aiBackendService.reviewHistory();
  }

  @GetMapping("/{runId}/measurements")
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
  @PutMapping("/{runId}/measurements")
  public List<MeasurementSaveDto> saveMeasurements(
      @Parameter(description = "Id de la corrida.") @PathVariable String runId,
      @RequestBody MeasurementBatchDto request) {
    return aiBackendService.saveMeasurements(runId, request);
  }

  @PostMapping("/{runId}/export")
  public ReviewExportResponseDto exportReview(
      @PathVariable String runId, @RequestBody ReviewExportRequestDto request) {
    return aiBackendService.exportReview(runId, request);
  }
}
