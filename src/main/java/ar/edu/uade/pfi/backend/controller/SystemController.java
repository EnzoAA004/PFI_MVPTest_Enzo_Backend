package ar.edu.uade.pfi.backend.controller;

import ar.edu.uade.pfi.backend.service.SystemDiagnosticsService;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/system")
public class SystemController {
    private final SystemDiagnosticsService systemDiagnosticsService;

    public SystemController(SystemDiagnosticsService systemDiagnosticsService) {
        this.systemDiagnosticsService = systemDiagnosticsService;
    }

    @GetMapping("/diagnostics")
    public Map<String, Object> diagnostics() {
        return systemDiagnosticsService.diagnostics();
    }

    @PostMapping("/warmup")
    public Map<String, Object> warmup() {
        return systemDiagnosticsService.warmup();
    }
}
