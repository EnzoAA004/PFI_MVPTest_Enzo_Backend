package ar.edu.uade.pfi.backend.controller;

import ar.edu.uade.pfi.backend.auth.RoleAuthorizationService;
import ar.edu.uade.pfi.backend.service.SystemDiagnosticsService;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Map;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/system")
public class SystemController {
    private final SystemDiagnosticsService systemDiagnosticsService;
    private final RoleAuthorizationService authorizationService;

    @Autowired
    public SystemController(SystemDiagnosticsService systemDiagnosticsService, RoleAuthorizationService authorizationService) {
        this.systemDiagnosticsService = systemDiagnosticsService;
        this.authorizationService = authorizationService;
    }

    /**
     * The only endpoint in AuthFilter.PUBLIC_LIVENESS_PATHS: intentionally returns
     * nothing beyond a status flag. No database, AI Module, secrets, or infra details —
     * see /api/system/diagnostics (ADMIN-only) for anything richer.
     */
    @GetMapping("/health")
    public Map<String, Object> health() {
        return Map.of("status", "ok");
    }

    @GetMapping("/diagnostics")
    public Map<String, Object> diagnostics(HttpServletRequest request) {
        authorizationService.requireAdmin(request, "system.diagnostics");
        return systemDiagnosticsService.diagnostics();
    }

    /** Triggers an AI Module warmup call — ADMIN-only, it is an operational action, not a read. */
    @PostMapping("/warmup")
    public Map<String, Object> warmup(HttpServletRequest request) {
        authorizationService.requireAdmin(request, "system.warmup");
        return systemDiagnosticsService.warmup();
    }
}
