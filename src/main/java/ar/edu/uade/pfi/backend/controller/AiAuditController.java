package ar.edu.uade.pfi.backend.controller;

import ar.edu.uade.pfi.backend.dto.AuditEventResponseDto;
import ar.edu.uade.pfi.backend.service.AuditService;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/ai/audit-events")
public class AiAuditController {
    private final AuditService auditService;

    public AiAuditController(AuditService auditService) {
        this.auditService = auditService;
    }

    @GetMapping
    public List<AuditEventResponseDto> findEvents(
        @RequestParam(required = false) String traceId,
        @RequestParam(required = false) String entityId
    ) {
        if (traceId != null && !traceId.isBlank()) return auditService.findByTraceId(traceId);
        if (entityId != null && !entityId.isBlank()) return auditService.findByEntityId(entityId);
        return List.of();
    }
}
