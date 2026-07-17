package ar.edu.uade.pfi.backend.config;

import ar.edu.uade.pfi.backend.service.AuditService;
import jakarta.servlet.http.HttpServletRequest;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.server.ResponseStatusException;

@RestControllerAdvice
public class ApiExceptionHandler {
    private static final Logger log = LoggerFactory.getLogger(ApiExceptionHandler.class);
    private final AuditService auditService;

    public ApiExceptionHandler() {
        this(null);
    }

    @Autowired
    public ApiExceptionHandler(AuditService auditService) {
        this.auditService = auditService;
    }

    @ExceptionHandler(ResponseStatusException.class)
    public ResponseEntity<Map<String, Object>> handleResponseStatusException(ResponseStatusException ex, HttpServletRequest request) {
        HttpStatus status = HttpStatus.valueOf(ex.getStatusCode().value());
        return buildError(status, codeForStatus(status), safeMessage(ex.getReason(), status), request, ex);
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, Object>> handleIllegalArgument(IllegalArgumentException ex, HttpServletRequest request) {
        return buildError(HttpStatus.BAD_REQUEST, "BAD_REQUEST", safeMessage(ex.getMessage(), HttpStatus.BAD_REQUEST), request, ex);
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<Map<String, Object>> handleValidation(MethodArgumentNotValidException ex, HttpServletRequest request) {
        String message = ex.getBindingResult().getFieldErrors().stream()
            .findFirst()
            .map(error -> error.getField() + ": " + error.getDefaultMessage())
            .orElse("Payload invalido");
        return buildError(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR", message, request, ex);
    }

    @ExceptionHandler(RuntimeException.class)
    public ResponseEntity<Map<String, Object>> handleRuntime(RuntimeException ex, HttpServletRequest request) {
        return buildError(HttpStatus.INTERNAL_SERVER_ERROR, "INTERNAL_ERROR", "Error interno del backend", request, ex);
    }

    private ResponseEntity<Map<String, Object>> buildError(
        HttpStatus status,
        String code,
        String message,
        HttpServletRequest request,
        Exception ex
    ) {
        String traceId = traceId(request);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("status", "error");
        body.put("code", code);
        body.put("message", message);
        body.put("traceId", traceId);
        body.put("path", request.getRequestURI());
        body.put("method", request.getMethod());
        body.put("timestamp", Instant.now().toString());
        body.put("humanReviewRequired", true);
        body.put("notClinicalDiagnosis", true);

        if (status.is5xxServerError()) {
            log.error("api_error traceId={} code={} status={} path={} message={}", traceId, code, status.value(), request.getRequestURI(), ex.getMessage(), ex);
        } else {
            log.warn("api_error traceId={} code={} status={} path={} message={}", traceId, code, status.value(), request.getRequestURI(), ex.getMessage());
        }
        auditError(traceId, code, status, request);
        return ResponseEntity.status(status)
            .header(TraceIdFilter.TRACE_ID_HEADER, traceId)
            .body(body);
    }

    private void auditError(String traceId, String code, HttpStatus status, HttpServletRequest request) {
        if (auditService == null) return;
        try {
            auditService.record("backend", "error.http", request.getRequestURI(), traceId, Map.of(
                "code", code,
                "status", status.value(),
                "method", request.getMethod(),
                "path", request.getRequestURI()
            ));
        } catch (RuntimeException ignored) {
            // Never mask the original API error with audit persistence problems.
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

    private String codeForStatus(HttpStatus status) {
        return switch (status) {
            case BAD_REQUEST -> "BAD_REQUEST";
            case UNAUTHORIZED -> "UNAUTHORIZED";
            case FORBIDDEN -> "FORBIDDEN";
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
            case UNAUTHORIZED -> "No autorizado";
            case FORBIDDEN -> "Acceso denegado";
            case NOT_FOUND -> "Recurso no encontrado";
            case CONFLICT -> "Conflicto de estado";
            default -> status.is5xxServerError() ? "Error interno del backend" : status.getReasonPhrase();
        };
    }
}
