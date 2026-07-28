package ar.edu.uade.pfi.backend.config;

import ar.edu.uade.pfi.backend.config.error.ApiErrorWriter;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Single source of truth for CORS (there used to be a second, overlapping CorsFilter
 * bean too, which risked emitting duplicate Access-Control-* headers). Origins come
 * exclusively from PFI_CORS_ALLOWED_ORIGINS (pfi.cors.allowed-origins) and the optional
 * PFI_CORS_ALLOWED_ORIGIN_PATTERNS (pfi.cors.allowed-origin-patterns, "*" glob only, e.g.
 * "https://*.vercel.app" for preview deployments) — no production domain is hardcoded
 * in Java. Because credentials are allowed, a literal "*" is never honored in either list.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 1)
public class CorsResponseFilter extends OncePerRequestFilter {
    private static final Set<String> ALLOWED_METHODS = Set.of("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS");
    private final Set<String> allowedOrigins;
    private final List<String> allowedOriginPatterns;
    private final ApiErrorWriter apiErrorWriter;

    public CorsResponseFilter(String allowedOrigins, String allowedOriginPatterns) {
        this(allowedOrigins, allowedOriginPatterns, null);
    }

    @Autowired
    public CorsResponseFilter(
        @Value("${pfi.cors.allowed-origins:http://localhost:5173,http://localhost:3000}") String allowedOrigins,
        @Value("${pfi.cors.allowed-origin-patterns:}") String allowedOriginPatterns,
        @Nullable ApiErrorWriter apiErrorWriter
    ) {
        this.allowedOrigins = splitTrimmed(allowedOrigins).filter(origin -> !"*".equals(origin)).collect(Collectors.toSet());
        this.allowedOriginPatterns = splitTrimmed(allowedOriginPatterns).filter(origin -> !"*".equals(origin)).toList();
        this.apiErrorWriter = apiErrorWriter != null ? apiErrorWriter : new ApiErrorWriter(new ObjectMapper());
    }

    private static java.util.stream.Stream<String> splitTrimmed(String value) {
        if (value == null || value.isBlank()) return java.util.stream.Stream.empty();
        return Arrays.stream(value.split(",")).map(String::trim).filter(v -> !v.isBlank());
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
        throws ServletException, IOException {
        String origin = request.getHeader("Origin");
        if (isAllowedOrigin(origin)) {
            response.setHeader("Access-Control-Allow-Origin", origin);
            response.setHeader("Vary", "Origin");
            response.setHeader("Access-Control-Allow-Credentials", "true");
            response.setHeader("Access-Control-Allow-Methods", "GET,POST,PUT,PATCH,DELETE,OPTIONS");
            response.setHeader("Access-Control-Allow-Headers", "Authorization,Content-Type,Accept,Origin,X-Requested-With,X-Trace-Id");
            response.setHeader("Access-Control-Expose-Headers", "Content-Disposition,X-Trace-Id");
            response.setHeader("Access-Control-Max-Age", "3600");
        }

        if ("OPTIONS".equalsIgnoreCase(request.getMethod())) {
            if (origin != null && !origin.isBlank() && !isAllowedOrigin(origin)) {
                apiErrorWriter.writeError(request, response, HttpServletResponse.SC_FORBIDDEN, "ACCESS_DENIED", "Origen no autorizado.");
                return;
            }
            String requestedMethod = request.getHeader("Access-Control-Request-Method");
            if (requestedMethod != null && !ALLOWED_METHODS.contains(requestedMethod.trim().toUpperCase(java.util.Locale.ROOT))) {
                apiErrorWriter.writeError(request, response, HttpServletResponse.SC_FORBIDDEN, "ACCESS_DENIED", "Metodo no autorizado.");
                return;
            }
            response.setStatus(HttpServletResponse.SC_NO_CONTENT);
            return;
        }

        filterChain.doFilter(request, response);
    }

    private boolean isAllowedOrigin(String origin) {
        if (origin == null || origin.isBlank()) return false;
        if (allowedOrigins.contains(origin)) return true;
        for (String pattern : allowedOriginPatterns) {
            if (matchesPattern(origin, pattern)) return true;
        }
        return false;
    }

    private boolean matchesPattern(String origin, String pattern) {
        int star = pattern.indexOf('*');
        if (star < 0) return origin.equals(pattern);
        String prefix = pattern.substring(0, star);
        String suffix = pattern.substring(star + 1);
        return origin.startsWith(prefix) && origin.endsWith(suffix) && origin.length() >= prefix.length() + suffix.length();
    }
}
