package ar.edu.uade.pfi.backend.client;

import ar.edu.uade.pfi.backend.config.error.ApiErrorCode;
import java.util.Map;
import org.springframework.http.HttpStatus;

/**
 * Maps the pfi.multiplanar-run.v2 structured error codes (AiStructuredErrorV2Dto.code) to a stable
 * backend HTTP status + {@link ApiErrorCode} pair. Unknown codes are never surfaced as-is: they
 * collapse to a generic upstream-error bucket so the backend's public error contract stays stable
 * even if the AI Module adds new codes. The upstream code string itself is never used to build the
 * backend's public code or message — only this fixed mapping table is (P10-B.1 §3/§7).
 */
final class AiMultiplanarV2ErrorCodeMapper {
  record Mapped(HttpStatus status, ApiErrorCode backendCode) {
    String publicMessage() {
      return backendCode.publicMessage();
    }
  }

  private static final Map<String, Mapped> KNOWN_CODES =
      Map.ofEntries(
          Map.entry(
              "INVALID_MULTIPLANAR_REQUEST",
              new Mapped(HttpStatus.BAD_REQUEST, ApiErrorCode.AI_INVALID_REQUEST)),
          Map.entry(
              "NO_PLANE_REQUESTED",
              new Mapped(HttpStatus.BAD_REQUEST, ApiErrorCode.AI_NO_PLANE_REQUESTED)),
          Map.entry(
              "INPUT_NOT_FOUND", new Mapped(HttpStatus.NOT_FOUND, ApiErrorCode.AI_INPUT_NOT_FOUND)),
          Map.entry(
              "MODEL_NOT_FOUND", new Mapped(HttpStatus.NOT_FOUND, ApiErrorCode.AI_MODEL_NOT_FOUND)),
          Map.entry(
              "MODEL_PLANE_MISMATCH",
              new Mapped(HttpStatus.CONFLICT, ApiErrorCode.AI_MODEL_PLANE_MISMATCH)),
          Map.entry(
              "MODEL_NOT_READY", new Mapped(HttpStatus.CONFLICT, ApiErrorCode.AI_MODEL_NOT_READY)),
          Map.entry(
              "CONTRACT_FALLBACK_DISABLED",
              new Mapped(HttpStatus.BAD_GATEWAY, ApiErrorCode.AI_CONTRACT_FALLBACK_DISABLED)),
          Map.entry(
              "REAL_INFERENCE_FAILED",
              new Mapped(HttpStatus.BAD_GATEWAY, ApiErrorCode.AI_REAL_INFERENCE_FAILED)),
          Map.entry(
              "INVALID_MULTIPLANAR_RESPONSE",
              new Mapped(HttpStatus.BAD_GATEWAY, ApiErrorCode.AI_INVALID_RESPONSE)),
          Map.entry(
              "UNSUPPORTED_INFERENCE_MODE",
              new Mapped(HttpStatus.BAD_REQUEST, ApiErrorCode.AI_UNSUPPORTED_INFERENCE_MODE)));

  static final Mapped UNKNOWN = new Mapped(HttpStatus.BAD_GATEWAY, ApiErrorCode.AI_MODULE_ERROR);
  static final Mapped TIMEOUT =
      new Mapped(HttpStatus.GATEWAY_TIMEOUT, ApiErrorCode.AI_MODULE_TIMEOUT);

  private AiMultiplanarV2ErrorCodeMapper() {}

  static Mapped resolve(String upstreamCode) {
    if (upstreamCode == null) return UNKNOWN;
    Mapped mapped = KNOWN_CODES.get(upstreamCode.trim());
    return mapped == null ? UNKNOWN : mapped;
  }
}
