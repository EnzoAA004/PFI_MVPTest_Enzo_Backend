package ar.edu.uade.pfi.backend.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 1)
public class CorsResponseFilter extends OncePerRequestFilter {
    private final Set<String> allowedOrigins;

    public CorsResponseFilter(
        @Value("${pfi.cors.allowed-origins:https://pfi-mvp-test-enzo-frontend.vercel.app,http://localhost:5173,http://localhost:4173,http://localhost:3000,http://127.0.0.1:5173}") String allowedOrigins
    ) {
        this.allowedOrigins = Arrays.stream(allowedOrigins.split(","))
            .map(String::trim)
            .filter(value -> !value.isBlank())
            .collect(Collectors.toSet());
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
            response.setStatus(HttpServletResponse.SC_NO_CONTENT);
            return;
        }

        filterChain.doFilter(request, response);
    }

    private boolean isAllowedOrigin(String origin) {
        if (origin == null || origin.isBlank()) return false;
        if (allowedOrigins.contains(origin)) return true;
        return origin.startsWith("https://pfi-mvp-test-enzo-frontend") && origin.endsWith(".vercel.app");
    }
}
