package ar.edu.uade.pfi.backend.auth;

import ar.edu.uade.pfi.backend.config.TraceIdFilter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.time.Instant;
import java.util.Set;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Fail-closed authentication gate. Anything not explicitly listed in PUBLIC_AUTH_PATHS
 * (or an OPTIONS preflight) requires a valid Bearer JWT; there is no wildcard fallback
 * that treats unrecognized paths as public.
 */
@Component
public class AuthFilter extends OncePerRequestFilter {
    public static final String AUTH_CLAIMS_ATTRIBUTE = "pfi.auth.claims";
    private static final Set<String> PUBLIC_AUTH_PATHS = Set.of(
        "/api/auth/register",
        "/api/auth/verify-registration",
        "/api/auth/login",
        "/api/auth/verify-login",
        "/api/auth/refresh",
        "/api/auth/demo-doctor",
        "/api/auth/logout"
    );
    private static final Set<String> PUBLIC_LIVENESS_PATHS = Set.of(
        "/api/system/health",
        "/api/ai/health",
        "/api/ai/models",
        "/api/system/warmup"
    );
    private static final Set<String> PENDING_ALLOWED_PATHS = Set.of("/api/auth/me", "/api/auth/settings");
    private final TokenService tokenService;
    private final boolean authEnabled;

    public AuthFilter(TokenService tokenService, @Value("${pfi.auth.enabled:true}") boolean authEnabled) {
        this.tokenService = tokenService;
        this.authEnabled = authEnabled;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
        throws ServletException, IOException {
        if (!authEnabled || isPublicRequest(request)) {
            filterChain.doFilter(request, response);
            return;
        }
        String header = request.getHeader("Authorization");
        if (header == null || !header.startsWith("Bearer ") || header.substring(7).trim().isEmpty()) {
            writeError(request, response, HttpServletResponse.SC_UNAUTHORIZED, "AUTHENTICATION_REQUIRED", "Autenticacion requerida.");
            return;
        }
        TokenService.Claims claims = tokenService.verify(header.substring(7).trim());
        if (claims == null) {
            writeError(request, response, HttpServletResponse.SC_UNAUTHORIZED, "AUTHENTICATION_REQUIRED", "Autenticacion requerida.");
            return;
        }
        if (claims.roles().contains("PENDING_APPROVAL") && !PENDING_ALLOWED_PATHS.contains(request.getRequestURI())) {
            writeError(request, response, HttpServletResponse.SC_FORBIDDEN, "ACCESS_DENIED", "No tiene permisos para realizar esta operacion.");
            return;
        }
        request.setAttribute(AUTH_CLAIMS_ATTRIBUTE, claims);
        filterChain.doFilter(request, response);
    }

    private boolean isPublicRequest(HttpServletRequest request) {
        String method = request.getMethod();
        String path = request.getRequestURI();
        if ("OPTIONS".equalsIgnoreCase(method)) return true;
        if (PUBLIC_AUTH_PATHS.contains(path)) return true;
        return PUBLIC_LIVENESS_PATHS.contains(path);
    }

    private void writeError(HttpServletRequest request, HttpServletResponse response, int status, String code, String message)
        throws IOException {
        response.setStatus(status);
        response.setContentType("application/json");
        response.setHeader("Cache-Control", "no-store");
        String traceId = traceId(request);
        response.setHeader(TraceIdFilter.TRACE_ID_HEADER, traceId);
        response.getWriter().write(
            "{\"status\":\"error\",\"code\":\"" + code + "\",\"message\":\"" + message
                + "\",\"traceId\":\"" + traceId + "\",\"timestamp\":\"" + Instant.now() + "\"}"
        );
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
