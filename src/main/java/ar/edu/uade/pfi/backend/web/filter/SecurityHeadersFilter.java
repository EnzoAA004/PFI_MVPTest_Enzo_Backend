package ar.edu.uade.pfi.backend.web.filter;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Backend-only response headers. This API never serves the frontend's HTML, so the CSP here is
 * scoped to what a pure JSON/binary-asset API needs (no script/style directives that would only
 * matter for an HTML document) — the frontend's own CSP is configured separately on Vercel.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 2)
public class SecurityHeadersFilter extends OncePerRequestFilter {
  @Override
  protected void doFilterInternal(
      HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
      throws ServletException, IOException {
    response.setHeader("X-Content-Type-Options", "nosniff");
    response.setHeader("Referrer-Policy", "no-referrer");
    response.setHeader("X-Frame-Options", "DENY");
    response.setHeader("Content-Security-Policy", "default-src 'none'; frame-ancestors 'none'");
    String path = request.getRequestURI();
    if (isSensitive(path)) {
      response.setHeader("Cache-Control", "no-store");
      response.setHeader("Pragma", "no-cache");
    }
    filterChain.doFilter(request, response);
  }

  private boolean isSensitive(String path) {
    return path != null && (path.startsWith("/api/auth") || path.equals("/api/system/diagnostics"));
  }
}
