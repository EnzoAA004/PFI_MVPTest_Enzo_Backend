package ar.edu.uade.pfi.backend.service;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

import ar.edu.uade.pfi.backend.auth.AuthService;
import ar.edu.uade.pfi.backend.client.AiServiceOperations;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.lang.reflect.Proxy;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * P10-B §14/§18-B: the ADMIN diagnostics endpoint's `observability` section is a sanitized,
 * fixed-shape snapshot — no per-request identifiers, secrets, or paths.
 */
class ObservabilityDiagnosticsTest {

  @Test
  void observabilitySectionIsPresentAndSanitizedWhenMetricsAreWired() {
    OperationalMetricsService metrics = new OperationalMetricsService();
    metrics.recordHttpRequest(200, 12);
    metrics.recordAiCall(true, 30);
    metrics.incrementAuditWriteFailures();

    @SuppressWarnings("unchecked")
    org.springframework.beans.factory.ObjectProvider<RunAssetContentStorage> assetProvider =
        mock(org.springframework.beans.factory.ObjectProvider.class);
    SystemDiagnosticsService service =
        new SystemDiagnosticsService(
            aiClient(),
            new PostgresReviewStoreService(new ObjectMapper(), "memory", ""),
            authServiceMock(),
            assetProvider,
            new ar.edu.uade.pfi.backend.config.AiServiceProperties("http://ai-module", 60, "v1"),
            false,
            "memory",
            false,
            metrics);

    Map<String, Object> diagnostics = service.diagnostics();
    @SuppressWarnings("unchecked")
    Map<String, Object> observability = (Map<String, Object>) diagnostics.get("observability");

    assertTrue(observability.containsKey("uptimeSeconds"));
    assertTrue(observability.containsKey("counters"));
    assertTrue(observability.containsKey("averageRequestDurationMs"));
    assertTrue(observability.containsKey("averageAiCallDurationMs"));
    assertTrue(observability.containsKey("auditWriteFailures"));
    assertTrue(observability.containsKey("timestamp"));

    String serialized = observability.toString();
    assertFalse(serialized.toLowerCase().contains("traceid"));
    assertFalse(serialized.toLowerCase().contains("runid"));
    assertFalse(serialized.toLowerCase().contains("caseid"));
    assertFalse(serialized.contains("@"));
    assertFalse(serialized.toLowerCase().contains("http://"));
    assertFalse(serialized.contains("C:\\"));
  }

  @Test
  void observabilitySectionDegradesGracefullyWhenMetricsIsAbsent() {
    SystemDiagnosticsService service =
        new SystemDiagnosticsService(
            aiClient(),
            new PostgresReviewStoreService(new ObjectMapper(), "memory", ""),
            authServiceMock(),
            false,
            "memory");

    Map<String, Object> diagnostics = service.diagnostics();
    @SuppressWarnings("unchecked")
    Map<String, Object> observability = (Map<String, Object>) diagnostics.get("observability");

    assertTrue(observability.containsKey("status"));
  }

  private AuthService authServiceMock() {
    AuthService authService = mock(AuthService.class);
    org.mockito.Mockito.when(authService.diagnostics())
        .thenReturn(Map.of("enabled", true, "status", "ok"));
    return authService;
  }

  private AiServiceOperations aiClient() {
    return (AiServiceOperations)
        Proxy.newProxyInstance(
            AiServiceOperations.class.getClassLoader(),
            new Class<?>[] {AiServiceOperations.class},
            (proxy, method, args) ->
                switch (method.getName()) {
                  case "health" -> Map.of("status", "ok");
                  case "readiness" ->
                      Map.of(
                          "status",
                          "contract_ready",
                          "readyForDemo",
                          true,
                          "readyForRealInference",
                          false);
                  case "models" -> Map.of("status", "ok");
                  case "getRecentAgentReports" ->
                      Map.of("status", "ok", "count", 0, "items", java.util.List.of());
                  case "getEvaluationEvidence" ->
                      Map.of(
                          "status", "evidence_summary_ready", "reportCount", 0, "latestRunId", "");
                  default -> throw new UnsupportedOperationException(method.getName());
                });
  }
}
