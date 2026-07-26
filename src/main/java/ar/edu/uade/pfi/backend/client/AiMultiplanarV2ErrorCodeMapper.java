package ar.edu.uade.pfi.backend.client;

import java.util.Map;
import org.springframework.http.HttpStatus;

/**
 * Maps the pfi.multiplanar-run.v2 structured error codes (AiStructuredErrorV2Dto.code)
 * to a stable backend HTTP status + error code pair. Unknown codes are never surfaced
 * as-is: they collapse to a generic upstream-error bucket so the backend's public
 * error contract stays stable even if the AI Module adds new codes.
 */
final class AiMultiplanarV2ErrorCodeMapper {
    record Mapped(HttpStatus status, String backendCode) {}

    private static final Map<String, Mapped> KNOWN_CODES = Map.ofEntries(
        Map.entry("INVALID_MULTIPLANAR_REQUEST", new Mapped(HttpStatus.BAD_REQUEST, "AI_INVALID_REQUEST")),
        Map.entry("NO_PLANE_REQUESTED", new Mapped(HttpStatus.BAD_REQUEST, "AI_NO_PLANE_REQUESTED")),
        Map.entry("INPUT_NOT_FOUND", new Mapped(HttpStatus.NOT_FOUND, "AI_INPUT_NOT_FOUND")),
        Map.entry("MODEL_NOT_FOUND", new Mapped(HttpStatus.NOT_FOUND, "AI_MODEL_NOT_FOUND")),
        Map.entry("MODEL_PLANE_MISMATCH", new Mapped(HttpStatus.CONFLICT, "AI_MODEL_PLANE_MISMATCH")),
        Map.entry("MODEL_NOT_READY", new Mapped(HttpStatus.CONFLICT, "AI_MODEL_NOT_READY")),
        Map.entry("CONTRACT_FALLBACK_DISABLED", new Mapped(HttpStatus.BAD_GATEWAY, "AI_CONTRACT_FALLBACK_DISABLED")),
        Map.entry("REAL_INFERENCE_FAILED", new Mapped(HttpStatus.BAD_GATEWAY, "AI_REAL_INFERENCE_FAILED")),
        Map.entry("INVALID_MULTIPLANAR_RESPONSE", new Mapped(HttpStatus.BAD_GATEWAY, "AI_INVALID_RESPONSE")),
        Map.entry("UNSUPPORTED_INFERENCE_MODE", new Mapped(HttpStatus.BAD_REQUEST, "AI_UNSUPPORTED_INFERENCE_MODE"))
    );

    static final Mapped UNKNOWN = new Mapped(HttpStatus.BAD_GATEWAY, "AI_MODULE_ERROR");
    static final Mapped TIMEOUT = new Mapped(HttpStatus.GATEWAY_TIMEOUT, "AI_MODULE_TIMEOUT");

    private AiMultiplanarV2ErrorCodeMapper() {}

    static Mapped resolve(String upstreamCode) {
        if (upstreamCode == null) return UNKNOWN;
        Mapped mapped = KNOWN_CODES.get(upstreamCode.trim());
        return mapped == null ? UNKNOWN : mapped;
    }
}
