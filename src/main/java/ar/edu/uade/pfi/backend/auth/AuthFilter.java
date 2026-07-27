package ar.edu.uade.pfi.backend.auth;

import ar.edu.uade.pfi.backend.config.TraceIdFilter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.env.Environment;
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
    private static final String DEMO_DOCTOR_PATH = "/api/auth/demo-doctor";
    private static final Set<String> PUBLIC_AUTH_PATHS = Set.of(
        "/api/auth/register",
        "/api/auth/verify-registration",
        "/api/auth/login",
        "/api/auth/verify-login",
        "/api/auth/refresh",
        "/api/auth/logout"
    );
    /** Only a minimal, no-detail liveness endpoint is anonymously reachable — see P10-A.1 §7. */
    private static final Set<String> PUBLIC_LIVENESS_PATHS = Set.of("/api/system/health");
    private static final Set<String> PENDING_ALLOWED_PATHS = Set.of("/api/auth/me", "/api/auth/settings");
    private final TokenService tokenService;
    private final AuthAccountStateService accountStateService;
    private final boolean authEnabled;
    private final boolean demoEnabled;
    private final Environment environment;

    public AuthFilter(
        TokenService tokenService,
        AuthAccountStateService accountStateService,
        @Value("${pfi.auth.enabled:true}") boolean authEnabled,
        @Value("${pfi.auth.demo-enabled:false}") boolean demoEnabled,
        Environment environment
    ) {
        this.tokenService = tokenService;
        this.accountStateService = accountStateService;
        this.authEnabled = authEnabled;
        this.demoEnabled = demoEnabled;
        this.environment = environment;
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
        TokenService.Claims tokenClaims = tokenService.verify(header.substring(7).trim());
        if (tokenClaims == null) {
            writeError(request, response, HttpServletResponse.SC_UNAUTHORIZED, "AUTHENTICATION_REQUIRED", "Autenticacion requerida.");
            return;
        }
        AuthAccountStateService.Resolution resolution = accountStateService.resolve(tokenClaims);
        if (resolution.status() == AuthAccountStateService.Status.STATE_UNAVAILABLE) {
            writeError(request, response, HttpServletResponse.SC_SERVICE_UNAVAILABLE, "AUTH_STATE_UNAVAILABLE",
                "No fue posible validar el estado actual de la sesion.");
            return;
        }
        if (resolution.status() != AuthAccountStateService.Status.OK) {
            writeError(request, response, HttpServletResponse.SC_UNAUTHORIZED, "AUTHENTICATION_REQUIRED", "Autenticacion requerida.");
            return;
        }
        TokenService.Claims claims = resolution.claims();
        if (isRestrictedAccount(claims) && !PENDING_ALLOWED_PATHS.contains(request.getRequestURI())) {
            writeError(request, response, HttpServletResponse.SC_FORBIDDEN, "ACCESS_DENIED", "No tiene permisos para realizar esta operacion.");
            return;
        }
        request.setAttribute(AUTH_CLAIMS_ATTRIBUTE, claims);
        filterChain.doFilter(request, response);
    }

    private static final Set<String> RECOGNIZED_ACTIVE_ROLES = Set.of("DOCTOR", "REVIEWER", "ADMIN");

    /**
     * A pending/inactive account: explicitly PENDING_APPROVAL, or carrying no
     * recognized professional/admin role at all (covers accounts with an empty or
     * unrecognized role set the same way PENDING_APPROVAL is covered).
     */
    private boolean isRestrictedAccount(TokenService.Claims claims) {
        List<String> roles = claims.roles();
        if (roles == null || roles.isEmpty()) return true;
        if (roles.contains("PENDING_APPROVAL")) return true;
        return roles.stream().noneMatch(RECOGNIZED_ACTIVE_ROLES::contains);
    }

    private boolean isPublicRequest(HttpServletRequest request) {
        String method = request.getMethod();
        String path = request.getRequestURI();
        if ("OPTIONS".equalsIgnoreCase(method)) return true;
        if (PUBLIC_AUTH_PATHS.contains(path)) return true;
        if (PUBLIC_LIVENESS_PATHS.contains(path)) return true;
        if (DEMO_DOCTOR_PATH.equals(path)) return isDemoEffectivelyEnabled();
        return false;
    }

    /** Demo seeding is only ever public when explicitly opted into locally AND not in production. */
    private boolean isDemoEffectivelyEnabled() {
        return demoEnabled && !isProductionProfile();
    }

    private boolean isProductionProfile() {
        if (environment == null) return false;
        for (String profile : environment.getActiveProfiles()) {
            String normalized = profile.toLowerCase(Locale.ROOT);
            if (normalized.equals("production") || normalized.equals("prod")) return true;
        }
        return false;
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
