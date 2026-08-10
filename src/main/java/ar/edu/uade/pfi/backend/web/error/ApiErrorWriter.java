package ar.edu.uade.pfi.backend.web.error;

import ar.edu.uade.pfi.backend.web.filter.TraceIdFilter;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Single place that builds and (for servlet filters, which run outside Spring MVC's own response
 * serialization) writes the API's one error contract — see {@link ApiErrorResponse}. Used by {@code
 * ApiExceptionHandler} (body only — Spring MVC serializes it), and directly by {@code
 * AuthFilter}/{@code CorsResponseFilter} (which must write to the raw response themselves, before
 * any controller/handler runs).
 *
 * <p>Deliberately narrow: never throws, never includes a stack trace, the original exception
 * message, the request body, the query string, or the Authorization header. Always sets
 * Content-Type/Cache-Control/X-Trace-Id.
 */
@Component
public class ApiErrorWriter {
  private static final Logger log = LoggerFactory.getLogger(ApiErrorWriter.class);
  private final ObjectMapper objectMapper;

  public ApiErrorWriter(ObjectMapper objectMapper) {
    this.objectMapper = objectMapper;
  }

  /**
   * Endpoints where a POST is presumed non-idempotent — retryable is always forced false there. See
   * class docs on retryable.
   */
  private static boolean isNonIdempotentInferenceEndpoint(String method, String path) {
    if (!"POST".equalsIgnoreCase(method) || path == null) return false;
    return path.contains("/multiplanar/run")
        || path.contains("/pipeline/run")
        || path.contains("/inputs");
  }

  /**
   * retryable = true only for a known-transient code (DATABASE_UNAVAILABLE, AUTH_STATE_UNAVAILABLE,
   * UPSTREAM_UNAVAILABLE, AI_TIMEOUT). An HTTP 502/503/504 is only used as a defensive fallback for
   * a code this catalog doesn't recognize ({@link ApiErrorCode#UNKNOWN}) — a *known*,
   * explicitly-classified non-transient code (e.g. AI_CONTRACT_VIOLATION, which is also mapped to
   * 502) must never be upgraded to retryable just because of its HTTP status. Either way, a POST to
   * a non-idempotent inference/upload endpoint always forces retryable back to false, since the
   * backend cannot guarantee it is safe to retry the underlying AI Module side effect there.
   */
  public boolean resolveRetryable(String code, int status, String method, String path) {
    ApiErrorCode errorCode = ApiErrorCode.fromCode(code);
    boolean transientCode = errorCode.transientByDefault();
    boolean unclassifiedGatewayStatus =
        errorCode == ApiErrorCode.UNKNOWN && (status == 502 || status == 503 || status == 504);
    if (!transientCode && !unclassifiedGatewayStatus) return false;
    return !isNonIdempotentInferenceEndpoint(method, path);
  }

  /** Builds the canonical {@link ApiErrorResponse} for this error. */
  public ApiErrorResponse response(
      String code, String message, String traceId, String path, String method, int status) {
    ApiErrorCode errorCode = ApiErrorCode.fromCode(code);
    return new ApiErrorResponse(
        "error",
        code,
        message,
        traceId,
        path,
        method,
        Instant.now().toString(),
        errorCode.category().name(),
        resolveRetryable(code, status, method, path),
        true,
        true);
  }

  /**
   * Same contract as {@link #response}, as an ordered Map — extensible for callers that add extra
   * domain fields (e.g. runId/plane/assetName).
   */
  public Map<String, Object> body(
      String code, String message, String traceId, String path, String method, int status) {
    ApiErrorResponse errorResponse = response(code, message, traceId, path, method, status);
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("status", errorResponse.status());
    body.put("code", errorResponse.code());
    body.put("message", errorResponse.message());
    body.put("traceId", errorResponse.traceId());
    body.put("path", errorResponse.path());
    body.put("method", errorResponse.method());
    body.put("timestamp", errorResponse.timestamp());
    body.put("category", errorResponse.category());
    body.put("retryable", errorResponse.retryable());
    body.put("humanReviewRequired", errorResponse.humanReviewRequired());
    body.put("notClinicalDiagnosis", errorResponse.notClinicalDiagnosis());
    return body;
  }

  /**
   * Direct servlet-response write for filters that run before/outside Spring MVC's own
   * serialization. Never throws.
   */
  public void writeError(
      HttpServletRequest request,
      HttpServletResponse response,
      int status,
      String code,
      String message) {
    String traceId = traceId(request);
    Map<String, Object> body =
        body(code, message, traceId, request.getRequestURI(), request.getMethod(), status);
    response.setStatus(status);
    response.setContentType("application/json");
    response.setHeader("Cache-Control", "no-store");
    response.setHeader(TraceIdFilter.TRACE_ID_HEADER, traceId);
    try {
      objectMapper.writeValue(response.getWriter(), body);
    } catch (IOException ex) {
      // The response is likely already committed/closed at this point — nothing safe
      // to do beyond noting it happened; must not let this escape the filter chain.
      log.warn("event=api_error_write_failed traceId={} status={}", traceId, status);
    }
  }

  private String traceId(HttpServletRequest request) {
    Object attribute = request.getAttribute(TraceIdFilter.TRACE_ID_ATTRIBUTE);
    if (attribute instanceof String value && !value.isBlank()) {
      return value;
    }
    String header = request.getHeader(TraceIdFilter.TRACE_ID_HEADER);
    return header == null || header.isBlank() ? "unavailable" : header;
  }
}
