package ar.edu.uade.pfi.backend.controller;

import ar.edu.uade.pfi.backend.dto.PipelineRunRequestDto;
import ar.edu.uade.pfi.backend.service.AiBackendService;
import ar.edu.uade.pfi.backend.web.error.ApiErrorResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.Map;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Executes the single-plane AI pipeline; schema endpoints remain in AiContractController. */
@RestController
@RequestMapping("/api/ai/pipeline")
@Tag(
    name = "IA - corridas y assets",
    description = "Ingesta de series, ejecucion de inferencia y acceso a los artefactos.")
public class AiPipelineController {
  private final AiBackendService aiBackendService;

  public AiPipelineController(AiBackendService aiBackendService) {
    this.aiBackendService = aiBackendService;
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
  @PostMapping("/run")
  public Map<String, Object> runPipeline(@Valid @RequestBody PipelineRunRequestDto request) {
    return aiBackendService.runPipeline(request);
  }
}
