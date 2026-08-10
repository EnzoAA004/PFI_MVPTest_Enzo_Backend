package ar.edu.uade.pfi.backend.controller;

import ar.edu.uade.pfi.backend.service.AiBackendService;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Read-only access to AI agent reports and summaries. */
@RestController
@RequestMapping("/api/ai/agent")
@Tag(
    name = "IA - corridas y assets",
    description = "Ingesta de series, ejecucion de inferencia y acceso a los artefactos.")
public class AiAgentReportController {
  private final AiBackendService aiBackendService;

  public AiAgentReportController(AiBackendService aiBackendService) {
    this.aiBackendService = aiBackendService;
  }

  @GetMapping("/reports")
  public Map<String, Object> getRecentAgentReports(@RequestParam(defaultValue = "20") int limit) {
    return aiBackendService.getRecentAgentReports(limit);
  }

  @GetMapping("/report/{runId}/summary")
  public Map<String, Object> getAgentReportSummary(@PathVariable String runId) {
    return aiBackendService.getAgentReportSummary(runId);
  }

  @GetMapping("/report/{runId}")
  public Map<String, Object> getAgentReport(@PathVariable String runId) {
    return aiBackendService.getAgentReport(runId);
  }
}
