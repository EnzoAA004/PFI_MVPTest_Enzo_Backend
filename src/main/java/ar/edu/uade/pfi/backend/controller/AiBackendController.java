package ar.edu.uade.pfi.backend.controller;

import ar.edu.uade.pfi.backend.auth.RoleAuthorizationService;
import ar.edu.uade.pfi.backend.service.AiBackendService;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Map;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Operational status endpoints for the backend-to-AI integration. */
@RestController
@RequestMapping("/api/ai")
@Tag(
    name = "IA - corridas y assets",
    description = "Ingesta de series, ejecucion de inferencia y acceso a los artefactos.")
public class AiBackendController {
  private final AiBackendService aiBackendService;
  private final RoleAuthorizationService authorizationService;

  @Autowired
  public AiBackendController(
      AiBackendService aiBackendService, RoleAuthorizationService authorizationService) {
    this.aiBackendService = aiBackendService;
    this.authorizationService = authorizationService;
  }

  /** Authenticated liveness for professional and administrative users. */
  @GetMapping("/health")
  public Map<String, Object> health() {
    return aiBackendService.health();
  }

  /** Technical diagnostic restricted to administrators. */
  @GetMapping("/readiness")
  public Map<String, Object> readiness(HttpServletRequest request) {
    authorizationService.requireAdmin(request, "ai.readiness");
    return aiBackendService.readiness();
  }

  /** Authenticated model registry status. */
  @GetMapping("/models")
  public Object models() {
    return aiBackendService.models();
  }

  /** Technical model verification restricted to administrators. */
  @GetMapping("/models/verify")
  public Map<String, Object> verifyModels(HttpServletRequest request) {
    authorizationService.requireAdmin(request, "ai.models.verify");
    return aiBackendService.verifyModels();
  }
}
