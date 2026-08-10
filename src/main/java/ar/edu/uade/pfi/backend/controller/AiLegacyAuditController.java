package ar.edu.uade.pfi.backend.controller;

import ar.edu.uade.pfi.backend.auth.RoleAuthorizationService;
import ar.edu.uade.pfi.backend.dto.AuditEventDto;
import ar.edu.uade.pfi.backend.dto.AuditEventRequestDto;
import ar.edu.uade.pfi.backend.service.AiBackendService;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Legacy audit endpoints retained independently from the canonical audit-event search API. */
@RestController
@RequestMapping("/api/ai/audit")
@Tag(
    name = "IA - corridas y assets",
    description = "Ingesta de series, ejecucion de inferencia y acceso a los artefactos.")
public class AiLegacyAuditController {
  private final AiBackendService aiBackendService;
  private final RoleAuthorizationService authorizationService;

  public AiLegacyAuditController(
      AiBackendService aiBackendService, RoleAuthorizationService authorizationService) {
    this.aiBackendService = aiBackendService;
    this.authorizationService = authorizationService;
  }

  @PostMapping
  public AuditEventDto appendAudit(
      @RequestBody AuditEventRequestDto request, HttpServletRequest httpRequest) {
    authorizationService.requireAdmin(httpRequest, "audit.trail");
    return aiBackendService.appendAudit(request);
  }

  @GetMapping
  public List<AuditEventDto> auditTrail(HttpServletRequest httpRequest) {
    authorizationService.requireAdmin(httpRequest, "audit.trail");
    return aiBackendService.auditTrail();
  }
}
