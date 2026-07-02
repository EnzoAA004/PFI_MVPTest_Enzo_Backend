package ar.edu.uade.pfi.backend.service;

import ar.edu.uade.pfi.backend.auth.AuthFilter;
import ar.edu.uade.pfi.backend.auth.TokenService;
import ar.edu.uade.pfi.backend.dto.AuditEventRequestDto;
import jakarta.servlet.http.HttpServletRequest;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;

@Service
public class ProfessionalAccessAuditService {
    private final ReviewStoreService reviewStoreService;

    public ProfessionalAccessAuditService(ReviewStoreService reviewStoreService) {
        this.reviewStoreService = reviewStoreService;
    }

    public void record(HttpServletRequest request, String action, String detail) {
        TokenService.Claims claims = claims(request);
        String reviewer = claims == null || claims.name().isBlank()
            ? "Authenticated professional"
            : claims.name();
        String enrichedDetail = detail;
        if (claims != null && claims.email() != null && !claims.email().isBlank()) {
            enrichedDetail += " | professional=" + claims.email();
        }
        if (claims != null && claims.roles() != null && !claims.roles().isEmpty()) {
            enrichedDetail += " | roles=" + claims.roles().stream().collect(Collectors.joining(","));
        }
        reviewStoreService.appendAudit(new AuditEventRequestDto(reviewer, action, enrichedDetail));
    }

    private TokenService.Claims claims(HttpServletRequest request) {
        Object value = request.getAttribute(AuthFilter.AUTH_CLAIMS_ATTRIBUTE);
        return value instanceof TokenService.Claims claims ? claims : null;
    }
}
