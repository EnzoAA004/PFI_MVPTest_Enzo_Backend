package ar.edu.uade.pfi.backend.auth;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Set;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

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
        if (header == null || !header.startsWith("Bearer ")) {
            response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
            response.setContentType("application/json");
            response.getWriter().write("{\"status\":\"unauthorized\",\"message\":\"JWT requerido para acceder a recursos de revision profesional\"}");
            return;
        }
        TokenService.Claims claims = tokenService.verify(header.substring(7).trim());
        if (claims == null) {
            response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
            response.setContentType("application/json");
            response.getWriter().write("{\"status\":\"unauthorized\",\"message\":\"Token invalido o expirado\"}");
            return;
        }
        if (claims.roles().contains("PENDING_APPROVAL") && !PENDING_ALLOWED_PATHS.contains(request.getRequestURI())) {
            response.setStatus(HttpServletResponse.SC_FORBIDDEN);
            response.setContentType("application/json");
            response.getWriter().write("{\"status\":\"pending_approval\",\"message\":\"Cuenta profesional pendiente de aprobacion institucional\"}");
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
        if (path.equals("/api/ai/health") || path.equals("/api/ai/models")) return true;
        if (path.equals("/api/system/warmup")) return true;
        return !path.startsWith("/api/");
    }
}
