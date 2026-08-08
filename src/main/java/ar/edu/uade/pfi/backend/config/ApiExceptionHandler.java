package ar.edu.uade.pfi.backend.config;

import ar.edu.uade.pfi.backend.auth.AdminAccountProtectedException;
import ar.edu.uade.pfi.backend.auth.LastAdminProtectionException;
import ar.edu.uade.pfi.backend.config.error.ApiErrorCode;
import ar.edu.uade.pfi.backend.config.error.ApiErrorWriter;
import ar.edu.uade.pfi.backend.service.AiContractViolationException;
import ar.edu.uade.pfi.backend.service.AiMultiplanarContractViolationException;
import ar.edu.uade.pfi.backend.service.AiMultiplanarUpstreamException;
import ar.edu.uade.pfi.backend.service.AssetContentUnavailableException;
import ar.edu.uade.pfi.backend.service.AuditService;
import ar.edu.uade.pfi.backend.service.DatabaseUnavailableException;
import ar.edu.uade.pfi.backend.service.OperationalMetricsService;
import ar.edu.uade.pfi.backend.service.RunReviewException;
import ar.edu.uade.pfi.backend.service.StudyMetadataException;
import ar.edu.uade.pfi.backend.service.StudyNotFoundException;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.lang.Nullable;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.server.ResponseStatusException;

/**
 * Single mapping point from any exception to the one API error contract (see {@code
 * ApiErrorResponse}/{@code ApiErrorWriter}). Never logs or returns the raw exception message on a
 * 5xx, the exception's fully-qualified class name, SQL, a JDBC URL, an AI Module URL, a local
 * filesystem path, or a stack trace in the response body.
 */
@RestControllerAdvice
public class ApiExceptionHandler {
  private static final Logger log = LoggerFactory.getLogger(ApiExceptionHandler.class);
  private final AuditService auditService;
  private final OperationalMetricsService metrics;
  private final ApiErrorWriter apiErrorWriter;

  public ApiExceptionHandler() {
    this(null, null);
  }

  public ApiExceptionHandler(AuditService auditService) {
    this(auditService, null);
  }

  @Autowired
  public ApiExceptionHandler(
      @Nullable AuditService auditService, @Nullable OperationalMetricsService metrics) {
    this.auditService = auditService;
    this.metrics = metrics;
    this.apiErrorWriter = new ApiErrorWriter(new ObjectMapper());
  }

  /**
   * A ResponseStatusException's own reason string is backend-authored at every current call site
   * (audited for P10-B.1 §2 — none interpolate upstream/user-controlled content), but is still run
   * through SafeLogSanitizer as defense in depth for 4xx, and is never trusted at all for 5xx — a
   * fixed catalog message is used there instead, matching every other 5xx handler in this class
   * (P10-B.1 §1).
   */
  @ExceptionHandler(ResponseStatusException.class)
  public ResponseEntity<Map<String, Object>> handleResponseStatusException(
      ResponseStatusException ex, HttpServletRequest request) {
    HttpStatus status = HttpStatus.valueOf(ex.getStatusCode().value());
    String code = codeForStatus(status);
    String message =
        status.is5xxServerError()
            ? ApiErrorCode.fromCode(code).publicMessage()
            : safeMessage(SafeLogSanitizer.sanitizeMessage(ex.getReason()), status);
    return buildError(status, code, message, request, ex);
  }

  @ExceptionHandler(AiContractViolationException.class)
  public ResponseEntity<Map<String, Object>> handleAiContractViolation(
      AiContractViolationException ex, HttpServletRequest request) {
    return buildError(
        HttpStatus.BAD_GATEWAY,
        "AI_CONTRACT_VIOLATION",
        ApiErrorCode.AI_CONTRACT_VIOLATION.publicMessage(),
        request,
        ex);
  }

  @ExceptionHandler(AiMultiplanarContractViolationException.class)
  public ResponseEntity<Map<String, Object>> handleAiMultiplanarContractViolation(
      AiMultiplanarContractViolationException ex, HttpServletRequest request) {
    return buildError(
        HttpStatus.BAD_GATEWAY,
        "AI_MULTIPLANAR_CONTRACT_VIOLATION",
        ApiErrorCode.AI_MULTIPLANAR_CONTRACT_VIOLATION.publicMessage(),
        request,
        ex);
  }

  /**
   * AiServiceClient already constructs this exception with a fixed catalog message (P10-B.1 §3) —
   * this handler re-derives the message from {@code ex.code()} anyway, rather than trusting {@code
   * ex.getMessage()} directly, so a future call site that forgets that discipline still can't leak
   * upstream text through here.
   */
  @ExceptionHandler(AiMultiplanarUpstreamException.class)
  public ResponseEntity<Map<String, Object>> handleAiMultiplanarUpstream(
      AiMultiplanarUpstreamException ex, HttpServletRequest request) {
    return buildError(
        ex.status(), ex.code(), ApiErrorCode.fromCode(ex.code()).publicMessage(), request, ex);
  }

  @ExceptionHandler(DatabaseUnavailableException.class)
  public ResponseEntity<Map<String, Object>> handleDatabaseUnavailable(
      DatabaseUnavailableException ex, HttpServletRequest request) {
    return buildError(
        HttpStatus.SERVICE_UNAVAILABLE,
        "DATABASE_UNAVAILABLE",
        ApiErrorCode.DATABASE_UNAVAILABLE.publicMessage(),
        request,
        ex);
  }

  @ExceptionHandler(StudyNotFoundException.class)
  public ResponseEntity<Map<String, Object>> handleStudyNotFound(
      StudyNotFoundException ex, HttpServletRequest request) {
    return buildError(
        HttpStatus.NOT_FOUND, "STUDY_NOT_FOUND", "Estudio no encontrado", request, ex);
  }

  @ExceptionHandler(AssetContentUnavailableException.class)
  public ResponseEntity<Map<String, Object>> handleAssetContentUnavailable(
      AssetContentUnavailableException ex, HttpServletRequest request) {
    ResponseEntity<Map<String, Object>> response =
        buildError(HttpStatus.NOT_FOUND, "ASSET_CONTENT_UNAVAILABLE", ex.getMessage(), request, ex);
    response.getBody().put("runId", ex.runId());
    response.getBody().put("plane", ex.plane());
    response.getBody().put("assetName", ex.assetName());
    return response;
  }

  @ExceptionHandler(RunReviewException.class)
  public ResponseEntity<Map<String, Object>> handleRunReview(
      RunReviewException ex, HttpServletRequest request) {
    return buildError(ex.status(), ex.code(), ex.getMessage(), request, ex);
  }

  @ExceptionHandler(StudyMetadataException.class)
  public ResponseEntity<Map<String, Object>> handleStudyMetadata(
      StudyMetadataException ex, HttpServletRequest request) {
    return buildError(ex.status(), ex.code(), ex.getMessage(), request, ex);
  }

  @ExceptionHandler(LastAdminProtectionException.class)
  public ResponseEntity<Map<String, Object>> handleLastAdminProtection(
      LastAdminProtectionException ex, HttpServletRequest request) {
    return buildError(ex.status(), ex.code(), ex.getMessage(), request, ex);
  }

  @ExceptionHandler(AdminAccountProtectedException.class)
  public ResponseEntity<Map<String, Object>> handleAdminAccountProtected(
      AdminAccountProtectedException ex, HttpServletRequest request) {
    return buildError(ex.status(), ex.code(), ex.getMessage(), request, ex);
  }

  /**
   * Malformed JSON or, notably, a strict DTO (JsonIgnoreProperties(ignoreUnknown = false))
   * rejecting an unexpected field — e.g. a professional-activation payload that tried to smuggle in
   * "roles" or "admin". Sanitized 400, never the raw Jackson parse error (which could otherwise
   * echo back request field names/values).
   */
  @ExceptionHandler(HttpMessageNotReadableException.class)
  public ResponseEntity<Map<String, Object>> handleUnreadableMessage(
      HttpMessageNotReadableException ex, HttpServletRequest request) {
    return buildError(HttpStatus.BAD_REQUEST, "BAD_REQUEST", "Solicitud invalida", request, ex);
  }

  @ExceptionHandler(IllegalArgumentException.class)
  public ResponseEntity<Map<String, Object>> handleIllegalArgument(
      IllegalArgumentException ex, HttpServletRequest request) {
    return buildError(
        HttpStatus.BAD_REQUEST,
        "BAD_REQUEST",
        safeMessage(ex.getMessage(), HttpStatus.BAD_REQUEST),
        request,
        ex);
  }

  @ExceptionHandler(MethodArgumentNotValidException.class)
  public ResponseEntity<Map<String, Object>> handleValidation(
      MethodArgumentNotValidException ex, HttpServletRequest request) {
    String message =
        ex.getBindingResult().getFieldErrors().stream()
            .findFirst()
            .map(error -> error.getField() + ": " + error.getDefaultMessage())
            .orElse("Payload invalido");
    return buildError(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR", message, request, ex);
  }

  @ExceptionHandler(MaxUploadSizeExceededException.class)
  public ResponseEntity<Map<String, Object>> handleMaxUploadSizeExceeded(
      MaxUploadSizeExceededException ex, HttpServletRequest request) {
    return buildError(
        HttpStatus.PAYLOAD_TOO_LARGE,
        "INPUT_TOO_LARGE",
        "El archivo medico supera el tamano maximo permitido para carga. Limite por defecto: 200MB.",
        request,
        ex);
  }

  /**
   * Catch-all. Never surfaces ex.getMessage() (which could contain SQL, a JDBC URL, an AI Module
   * URL, or a local path) in either the body or the log line — only the sanitized, fixed public
   * message and the exception's simple class name.
   */
  @ExceptionHandler(RuntimeException.class)
  public ResponseEntity<Map<String, Object>> handleRuntime(
      RuntimeException ex, HttpServletRequest request) {
    return buildError(
        HttpStatus.INTERNAL_SERVER_ERROR,
        "INTERNAL_ERROR",
        ApiErrorCode.INTERNAL_ERROR.publicMessage(),
        request,
        ex);
  }

  private ResponseEntity<Map<String, Object>> buildError(
      HttpStatus status, String code, String message, HttpServletRequest request, Exception ex) {
    String traceId = traceId(request);
    Map<String, Object> body =
        apiErrorWriter.body(
            code, message, traceId, request.getRequestURI(), request.getMethod(), status.value());

    logError(status, code, traceId, request, ex, (Boolean) body.get("retryable"));
    recordMetrics(status, code);
    auditError(traceId, code, status, request);
    return ResponseEntity.status(status).header(TraceIdFilter.TRACE_ID_HEADER, traceId).body(body);
  }

  /**
   * Structured, sanitized log line — never ex.getMessage() and never the exception's
   * fully-qualified name (package + class), only its simple class name. A sanitized stack is only
   * emitted at DEBUG (never in the public body, never at WARN/ERROR by default) so it stays
   * available for local troubleshooting without becoming the default production log volume/leak
   * surface.
   */
  private void logError(
      HttpStatus status,
      String code,
      String traceId,
      HttpServletRequest request,
      Exception ex,
      boolean retryable) {
    String exceptionType = ex.getClass().getSimpleName();
    String category = ApiErrorCode.fromCode(code).category().name();
    String line =
        "event=api_error traceId={} code={} category={} status={} method={} path={} exceptionType={} retryable={}";
    Object[] args = {
      traceId,
      code,
      category,
      status.value(),
      request.getMethod(),
      request.getRequestURI(),
      exceptionType,
      retryable
    };
    if (status.is5xxServerError()) {
      log.error(line, args);
    } else {
      log.warn(line, args);
    }
    if (log.isDebugEnabled()) {
      log.debug(
          "event=api_error_detail traceId={} exceptionType={} message={}",
          traceId,
          exceptionType,
          SafeLogSanitizer.sanitizeMessage(ex.getMessage()));
    }
  }

  /**
   * P10-B.1 §13: AI_MULTIPLANAR_CONTRACT_VIOLATION is deliberately NOT counted here —
   * AiServiceClient is the single authority for that code (it's the only place that can see the
   * full HTTP+adapter+validator pipeline for a multiplanar run and increment exactly once), so
   * counting it again here would double-count every such violation. AI_CONTRACT_VIOLATION (the
   * older, single-plane/pipeline path, raised from AiBackendService rather than AiServiceClient)
   * has no other counter, so it stays here.
   */
  private void recordMetrics(HttpStatus status, String code) {
    if (metrics == null) return;
    if (status == HttpStatus.UNAUTHORIZED) {
      metrics.incrementAuthenticationFailures();
    } else if (status == HttpStatus.FORBIDDEN) {
      metrics.incrementAuthorizationDenials();
    } else if ("DATABASE_UNAVAILABLE".equals(code)) {
      metrics.incrementDatabaseUnavailable();
    } else if ("AI_CONTRACT_VIOLATION".equals(code)) {
      metrics.incrementAiContractViolations();
    }
  }

  private void auditError(
      String traceId, String code, HttpStatus status, HttpServletRequest request) {
    if (auditService == null) return;
    auditService.record(
        "backend",
        "error.http",
        request.getRequestURI(),
        traceId,
        Map.of(
            "code", code,
            "status", status.value(),
            "method", request.getMethod(),
            "path", request.getRequestURI()));
  }

  private String traceId(HttpServletRequest request) {
    Object attribute = request.getAttribute(TraceIdFilter.TRACE_ID_ATTRIBUTE);
    if (attribute instanceof String value && !value.isBlank()) {
      return value;
    }
    String header = request.getHeader(TraceIdFilter.TRACE_ID_HEADER);
    return header == null || header.isBlank() ? "unavailable" : header;
  }

  private String codeForStatus(HttpStatus status) {
    return switch (status) {
      case BAD_REQUEST -> "BAD_REQUEST";
      case UNAUTHORIZED -> "AUTHENTICATION_REQUIRED";
      case FORBIDDEN -> "ACCESS_DENIED";
      case NOT_FOUND -> "NOT_FOUND";
      case CONFLICT -> "CONFLICT";
      case SERVICE_UNAVAILABLE, BAD_GATEWAY, GATEWAY_TIMEOUT -> "UPSTREAM_UNAVAILABLE";
      default -> status.is4xxClientError() ? "CLIENT_ERROR" : "INTERNAL_ERROR";
    };
  }

  private String safeMessage(String value, HttpStatus status) {
    if (value != null && !value.isBlank()) {
      return value;
    }
    return switch (status) {
      case BAD_REQUEST -> "Solicitud invalida";
      case UNAUTHORIZED -> "Autenticacion requerida.";
      case FORBIDDEN -> "No tiene permisos para realizar esta operacion.";
      case NOT_FOUND -> "Recurso no encontrado";
      case CONFLICT -> "Conflicto de estado";
      default -> status.is5xxServerError() ? "Error interno del backend" : status.getReasonPhrase();
    };
  }
}
