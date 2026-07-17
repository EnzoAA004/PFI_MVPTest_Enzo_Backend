package ar.edu.uade.pfi.backend.auth;

import ar.edu.uade.pfi.backend.config.TraceIdFilter;
import ar.edu.uade.pfi.backend.service.AuditService;
import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

@Service
public class RoleAuthorizationService {
    private final AuditService auditService;

    public RoleAuthorizationService(AuditService auditService) {
        this.auditService = auditService;
    }

    public void requireAdmin(HttpServletRequest request, String entityId) {
        requireAnyRole(request, entityId, "ADMIN", List.of("ADMIN"));
    }

    public void requireProfessional(HttpServletRequest request, String entityId) {
        requireAnyRole(request, entityId, "PROFESSIONAL", List.of("REVIEWER", "DOCTOR", "ADMIN"));
    }

    private void requireAnyRole(HttpServletRequest request, String entityId, String semanticRole, List<String> allowedRoles) {
        TokenService.Claims claims = claims(request);
        List<String> currentRoles = claims == null || claims.roles() == null ? List.of() : claims.roles();
        boolean allowed = currentRoles.stream().anyMatch(allowedRoles::contains);
        if (allowed) {
            return;
        }
        auditDenied(request, claims, entityId, semanticRole, currentRoles);
        throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Rol insuficiente");
    }

    private TokenService.Claims claims(HttpServletRequest request) {
        Object value = request.getAttribute(AuthFilter.AUTH_CLAIMS_ATTRIBUTE);
        return value instanceof TokenService.Claims claims ? claims : null;
    }

    private void auditDenied(
        HttpServletRequest request,
        TokenService.Claims claims,
        String entityId,
        String semanticRole,
        List<String> currentRoles
    ) {
        if (auditService == null) return;
        try {
            auditService.record(
                claims == null || claims.subject().isBlank() ? "anonymous" : claims.subject(),
                "access.denied",
                entityId,
                traceId(request),
                Map.of(
                    "requiredRole", semanticRole,
                    "roles", currentRoles,
                    "method", request.getMethod()
                )
            );
        } catch (RuntimeException ignored) {
            // Authorization result must not depend on audit persistence availability.
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
}
