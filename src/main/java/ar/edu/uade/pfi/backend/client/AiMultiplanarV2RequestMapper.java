package ar.edu.uade.pfi.backend.client;

import ar.edu.uade.pfi.backend.dto.AiMultiplanarV2OptionsDto;
import ar.edu.uade.pfi.backend.dto.AiMultiplanarV2PlanesRequestDto;
import ar.edu.uade.pfi.backend.dto.AiMultiplanarV2RequestDto;
import ar.edu.uade.pfi.backend.dto.AiPlaneExecutionV2RequestDto;
import ar.edu.uade.pfi.backend.dto.MultiplanarRunRequestDto;
import java.util.Set;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

/**
 * Converts the backend's internal technical request (already stripped of studyMetadata and other
 * identifying fields by the controller) into the typed pfi.multiplanar-run.v2 wire contract. Never
 * forwards inputPath, free-form metadata, or backend trace bookkeeping.
 */
@Component
public class AiMultiplanarV2RequestMapper {
  private static final Set<String> ALLOWED_INFERENCE_MODES = Set.of("real_baseline", "demo");
  private static final int FIXED_SLICE_WINDOW_RADIUS = 3;

  public AiMultiplanarV2RequestDto toV2Request(MultiplanarRunRequestDto request, String traceId) {
    require(request != null, "request multiplanar vacio");
    require(!blank(request.caseId()), "caseId es obligatorio para el contrato v2");

    String inferenceMode = inferenceMode(request);
    require(
        ALLOWED_INFERENCE_MODES.contains(inferenceMode),
        "inferenceMode no permitido para el contrato v2: '" + inferenceMode + "'");

    boolean allowContractFallback = Boolean.TRUE.equals(request.allowContractFallback());
    require(
        !allowContractFallback,
        "allowContractFallback debe ser false para el contrato v2 en produccion real");

    AiPlaneExecutionV2RequestDto sagittal =
        plane(
            request.sagittalInputId(),
            request.sagittalInputPath(),
            request.sagittalModelKey(),
            "sagittal");
    AiPlaneExecutionV2RequestDto axial =
        plane(request.axialInputId(), request.axialInputPath(), request.axialModelKey(), "axial");
    require(
        sagittal != null || axial != null,
        "al menos un plano (sagittal o axial) es obligatorio para el contrato v2");

    return new AiMultiplanarV2RequestDto(
        request.caseId().trim(),
        traceId,
        inferenceMode,
        false,
        new AiMultiplanarV2PlanesRequestDto(sagittal, axial),
        new AiMultiplanarV2OptionsDto(null, null, FIXED_SLICE_WINDOW_RADIUS, null));
  }

  private AiPlaneExecutionV2RequestDto plane(
      String inputId, String inputPath, String modelKey, String planeName) {
    boolean idPresent = !blank(inputId);
    boolean pathPresent = !blank(inputPath);
    if (!idPresent && !pathPresent) return null;
    if (!idPresent) {
      throw new ResponseStatusException(
          HttpStatus.BAD_REQUEST,
          "El contrato v2 requiere inputId para el plano "
              + planeName
              + "; inputPath no es compatible con /v2/multiplanar/run");
    }
    require(
        !blank(modelKey),
        "modelKey es obligatorio para el plano " + planeName + " en el contrato v2");
    return new AiPlaneExecutionV2RequestDto(inputId.trim(), modelKey.trim());
  }

  private String inferenceMode(MultiplanarRunRequestDto request) {
    if (request.metadata() == null) return "";
    Object value = request.metadata().get("inferenceMode");
    return value == null ? "" : String.valueOf(value).trim();
  }

  private void require(boolean condition, String message) {
    if (!condition) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, message);
  }

  private boolean blank(String value) {
    return value == null || value.isBlank();
  }
}
