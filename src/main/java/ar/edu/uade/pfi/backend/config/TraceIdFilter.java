package ar.edu.uade.pfi.backend.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
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

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
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
            log.info(
                "request traceId={} method={} path={} status={} durationMs={}",
                traceId,
                request.getMethod(),
                request.getRequestURI(),
                response.getStatus(),
                elapsedMs
            );
            MDC.remove(TRACE_ID_MDC_KEY);
        }
    }

    private String resolveTraceId(String incomingTraceId) {
        if (incomingTraceId == null || incomingTraceId.isBlank()) {
            return generateTraceId();
        }
        String sanitized = incomingTraceId.trim().replaceAll("[^a-zA-Z0-9._:-]", "-");
        if (sanitized.isBlank()) {
            return generateTraceId();
        }
        return sanitized.length() > MAX_TRACE_ID_LENGTH ? sanitized.substring(0, MAX_TRACE_ID_LENGTH) : sanitized;
    }

    private String generateTraceId() {
        return "trace-" + UUID.randomUUID();
    }
}
