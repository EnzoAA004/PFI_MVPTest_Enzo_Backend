package ar.edu.uade.pfi.backend.controller;

import ar.edu.uade.pfi.backend.auth.RoleAuthorizationService;
import ar.edu.uade.pfi.backend.dto.AuditEventResponseDto;
import ar.edu.uade.pfi.backend.service.AuditService;
import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * System-wide audit trail search (arbitrary traceId/entityId lookups across all users
 * and cases) is an administrative capability, not a clinical/academic one, so it
 * requires ADMIN.
 */
@RestController
@RequestMapping("/api/ai/audit-events")
public class AiAuditController {
    private final AuditService auditService;
    private final RoleAuthorizationService authorizationService;

    public AiAuditController(AuditService auditService) {
        this(auditService, null);
    }

    @Autowired
    public AiAuditController(AuditService auditService, RoleAuthorizationService authorizationService) {
        this.auditService = auditService;
        this.authorizationService = authorizationService;
    }

    @GetMapping
    public List<AuditEventResponseDto> findEvents(
        @RequestParam(required = false) String traceId,
        @RequestParam(required = false) String entityId,
        HttpServletRequest request
    ) {
        if (authorizationService != null) {
            authorizationService.requireAdmin(request, "audit.events");
        }
        if (traceId != null && !traceId.isBlank()) return auditService.findByTraceId(traceId);
        if (entityId != null && !entityId.isBlank()) return auditService.findByEntityId(entityId);
        return List.of();
    }
}
