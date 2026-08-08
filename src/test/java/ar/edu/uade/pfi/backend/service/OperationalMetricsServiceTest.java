package ar.edu.uade.pfi.backend.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;
import org.junit.jupiter.api.Test;

class OperationalMetricsServiceTest {

  @Test
  void httpRequestIncrementsCorrectStatusBucket() {
    OperationalMetricsService metrics = new OperationalMetricsService();

    metrics.recordHttpRequest(200, 10);
    metrics.recordHttpRequest(201, 5);
    metrics.recordHttpRequest(404, 3);
    metrics.recordHttpRequest(500, 8);

    Map<String, Object> counters = counters(metrics);
    assertEquals(4L, counters.get("httpRequestsTotal"));
    assertEquals(2L, counters.get("httpResponses2xx"));
    assertEquals(1L, counters.get("httpResponses4xx"));
    assertEquals(1L, counters.get("httpResponses5xx"));
  }

  @Test
  void authenticationAndAuthorizationCountersAreIndependent() {
    OperationalMetricsService metrics = new OperationalMetricsService();

    metrics.incrementAuthenticationFailures();
    metrics.incrementAuthenticationFailures();
    metrics.incrementAuthorizationDenials();
    metrics.incrementAuthStateUnavailable();

    Map<String, Object> counters = counters(metrics);
    assertEquals(2L, counters.get("authenticationFailures"));
    assertEquals(1L, counters.get("authorizationDenials"));
    assertEquals(1L, counters.get("authStateUnavailable"));
  }

  @Test
  void aiCallSuccessAndFailureAreCountedSeparately() {
    OperationalMetricsService metrics = new OperationalMetricsService();

    metrics.recordAiCall(true, 100);
    metrics.recordAiCall(true, 200);
    metrics.recordAiCall(false, 50);

    Map<String, Object> counters = counters(metrics);
    assertEquals(3L, counters.get("aiCallsTotal"));
    assertEquals(2L, counters.get("aiCallsSucceeded"));
    assertEquals(1L, counters.get("aiCallsFailed"));
  }

  @Test
  void auditWriteFailuresAndReviewsSavedAreCounted() {
    OperationalMetricsService metrics = new OperationalMetricsService();

    metrics.incrementAuditWriteFailures();
    metrics.incrementReviewsSaved();
    metrics.incrementReviewsSaved();

    Map<String, Object> counters = counters(metrics);
    assertEquals(1L, counters.get("auditWriteFailures"));
    assertEquals(2L, counters.get("reviewsSaved"));
  }

  @Test
  void averageDurationsAreComputedCorrectly() {
    OperationalMetricsService metrics = new OperationalMetricsService();
    metrics.recordHttpRequest(200, 100);
    metrics.recordHttpRequest(200, 300);
    metrics.recordAiCall(true, 40);
    metrics.recordAiCall(true, 60);

    Map<String, Object> snapshot = metrics.snapshot();
    assertEquals(200L, snapshot.get("averageRequestDurationMs"));
    assertEquals(50L, snapshot.get("averageAiCallDurationMs"));
  }

  @Test
  void snapshotHasFixedShapeWithNoCardinalityByRequestData() {
    OperationalMetricsService metrics = new OperationalMetricsService();
    metrics.recordHttpRequest(200, 10);

    Map<String, Object> snapshot = metrics.snapshot();

    assertTrue(snapshot.containsKey("uptimeSeconds"));
    assertTrue(snapshot.containsKey("counters"));
    assertTrue(snapshot.containsKey("averageRequestDurationMs"));
    assertTrue(snapshot.containsKey("averageAiCallDurationMs"));
    assertTrue(snapshot.containsKey("auditWriteFailures"));
    assertTrue(snapshot.containsKey("timestamp"));
    // Exactly the fixed key set — no per-request/per-trace dynamic keys.
    assertEquals(6, snapshot.size());

    @SuppressWarnings("unchecked")
    Map<String, Object> counters = (Map<String, Object>) snapshot.get("counters");
    assertEquals(14, counters.size());
    assertFalse(snapshot.toString().contains("traceId"));
  }

  @SuppressWarnings("unchecked")
  private Map<String, Object> counters(OperationalMetricsService metrics) {
    return (Map<String, Object>) metrics.snapshot().get("counters");
  }
}
