package ar.edu.uade.pfi.backend.web.filter;

import ar.edu.uade.pfi.backend.auth.AuthFilter;
import ar.edu.uade.pfi.backend.service.OperationalMetricsService;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class TraceIdFilter extends OncePerRequestFilter {
  public static final String TRACE_ID_HEADER = "X-Trace-Id";
  public static final String TRACE_ID_MDC_KEY = "traceId";
  public static final String TRACE_ID_ATTRIBUTE = "pfi.traceId";
  private static final Logger log = LoggerFactory.getLogger(TraceIdFilter.class);
  private static final int MAX_TRACE_ID_LENGTH = 96;
  private final OperationalMetricsService metrics;

  public TraceIdFilter() {
    this(null);
  }

  @Autowired
  public TraceIdFilter(@Nullable OperationalMetricsService metrics) {
    this.metrics = metrics;
  }

  @Override
  protected void doFilterInternal(
      HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
      throws ServletException, IOException {
    String traceId = resolveTraceId(request.getHeader(TRACE_ID_HEADER));
    long startedAt = System.currentTimeMillis();
    request.setAttribute(TRACE_ID_ATTRIBUTE, traceId);
    response.setHeader(TRACE_ID_HEADER, traceId);
    MDC.put(TRACE_ID_MDC_KEY, traceId);
    try {
      filterChain.doFilter(request, response);
    } finally {
      long elapsedMs = System.currentTimeMillis() - startedAt;
      int status = response.getStatus();
      logRequest(request, traceId, status, elapsedMs);
      if (metrics != null) {
        metrics.recordHttpRequest(status, elapsedMs);
      }
      MDC.remove(TRACE_ID_MDC_KEY);
    }
  }

  /**
   * Stable, greppable structured line. actorId/roles are added only when AuthFilter already placed
   * them on the request (i.e. the caller was authenticated and revalidated) — never derived from
   * the raw, unrevalidated token here. Never includes email, name, request body, query string, case
   * metadata, file names, or the Authorization header.
   */
  private void logRequest(HttpServletRequest request, String traceId, int status, long elapsedMs) {
    String outcome = outcomeFor(status);
    Object actorId = request.getAttribute(AuthFilter.ACTOR_ID_ATTRIBUTE);
    Object roles = request.getAttribute(AuthFilter.ACTOR_ROLES_ATTRIBUTE);
    if (actorId instanceof String actor && !actor.isBlank()) {
      log.info(
          "event=http_request traceId={} method={} path={} status={} durationMs={} outcome={} actorId={} roles={}",
          traceId,
          request.getMethod(),
          request.getRequestURI(),
          status,
          elapsedMs,
          outcome,
          actor,
          roles);
    } else {
      log.info(
          "event=http_request traceId={} method={} path={} status={} durationMs={} outcome={}",
          traceId,
          request.getMethod(),
          request.getRequestURI(),
          status,
          elapsedMs,
          outcome);
    }
  }

  private String outcomeFor(int status) {
    if (status >= 200 && status < 400) return "success";
    if (status >= 400 && status < 500) return "client_error";
    return "server_error";
  }

  private String resolveTraceId(String incomingTraceId) {
    if (incomingTraceId == null || incomingTraceId.isBlank()) {
      return generateTraceId();
    }
    String sanitized = incomingTraceId.trim().replaceAll("[^a-zA-Z0-9._:-]", "-");
    if (sanitized.isBlank()) {
      return generateTraceId();
    }
    return sanitized.length() > MAX_TRACE_ID_LENGTH
        ? sanitized.substring(0, MAX_TRACE_ID_LENGTH)
        : sanitized;
  }

  private String generateTraceId() {
    return "trace-" + UUID.randomUUID();
  }
}
