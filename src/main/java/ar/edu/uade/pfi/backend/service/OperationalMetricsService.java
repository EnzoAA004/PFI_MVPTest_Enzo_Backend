package ar.edu.uade.pfi.backend.service;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.LongAdder;
import org.springframework.stereotype.Component;

/**
 * In-process operational counters — fixed, low-cardinality keys only. This is a stopgap until GCP
 * (Cloud Monitoring) is wired up; see docs/P10_B_ERRORS_AUDIT_OBSERVABILITY.md for the honest
 * limitations (in-memory, resets on redeploy/restart, single instance, not a Prometheus/OTel
 * exporter).
 *
 * <p>Deliberately never keyed by traceId/caseId/runId/email/userId/an arbitrary path, or an
 * exception message — every counter here is a fixed, enumerable field, not a dynamic map keyed by
 * request data.
 */
@Component
public class OperationalMetricsService {
  private final Instant startedAt = Instant.now();

  private final LongAdder httpRequestsTotal = new LongAdder();
  private final LongAdder httpResponses2xx = new LongAdder();
  private final LongAdder httpResponses4xx = new LongAdder();
  private final LongAdder httpResponses5xx = new LongAdder();
  private final LongAdder authenticationFailures = new LongAdder();
  private final LongAdder authorizationDenials = new LongAdder();
  private final LongAdder authStateUnavailable = new LongAdder();
  private final LongAdder databaseUnavailable = new LongAdder();
  private final LongAdder aiCallsTotal = new LongAdder();
  private final LongAdder aiCallsSucceeded = new LongAdder();
  private final LongAdder aiCallsFailed = new LongAdder();
  private final LongAdder aiContractViolations = new LongAdder();
  private final LongAdder reviewsSaved = new LongAdder();
  private final LongAdder auditWriteFailures = new LongAdder();

  private final LongAdder totalRequestDurationMs = new LongAdder();
  private final LongAdder aiCallDurationMs = new LongAdder();
  private final LongAdder aiCallCountForAverage = new LongAdder();

  public void recordHttpRequest(int status, long durationMs) {
    httpRequestsTotal.increment();
    totalRequestDurationMs.add(Math.max(0, durationMs));
    if (status >= 200 && status < 300) {
      httpResponses2xx.increment();
    } else if (status >= 400 && status < 500) {
      httpResponses4xx.increment();
    } else if (status >= 500) {
      httpResponses5xx.increment();
    }
  }

  public void incrementAuthenticationFailures() {
    authenticationFailures.increment();
  }

  public void incrementAuthorizationDenials() {
    authorizationDenials.increment();
  }

  public void incrementAuthStateUnavailable() {
    authStateUnavailable.increment();
  }

  public void incrementDatabaseUnavailable() {
    databaseUnavailable.increment();
  }

  public void recordAiCall(boolean succeeded, long durationMs) {
    aiCallsTotal.increment();
    aiCallDurationMs.add(Math.max(0, durationMs));
    aiCallCountForAverage.increment();
    if (succeeded) {
      aiCallsSucceeded.increment();
    } else {
      aiCallsFailed.increment();
    }
  }

  public void incrementAiContractViolations() {
    aiContractViolations.increment();
  }

  public void incrementReviewsSaved() {
    reviewsSaved.increment();
  }

  public void incrementAuditWriteFailures() {
    auditWriteFailures.increment();
  }

  public long uptimeSeconds() {
    return Math.max(0, Instant.now().getEpochSecond() - startedAt.getEpochSecond());
  }

  /** Sanitized, fixed-shape snapshot suitable for the ADMIN diagnostics endpoint. */
  public Map<String, Object> snapshot() {
    Map<String, Object> counters = new LinkedHashMap<>();
    counters.put("httpRequestsTotal", httpRequestsTotal.sum());
    counters.put("httpResponses2xx", httpResponses2xx.sum());
    counters.put("httpResponses4xx", httpResponses4xx.sum());
    counters.put("httpResponses5xx", httpResponses5xx.sum());
    counters.put("authenticationFailures", authenticationFailures.sum());
    counters.put("authorizationDenials", authorizationDenials.sum());
    counters.put("authStateUnavailable", authStateUnavailable.sum());
    counters.put("databaseUnavailable", databaseUnavailable.sum());
    counters.put("aiCallsTotal", aiCallsTotal.sum());
    counters.put("aiCallsSucceeded", aiCallsSucceeded.sum());
    counters.put("aiCallsFailed", aiCallsFailed.sum());
    counters.put("aiContractViolations", aiContractViolations.sum());
    counters.put("reviewsSaved", reviewsSaved.sum());
    counters.put("auditWriteFailures", auditWriteFailures.sum());

    Map<String, Object> snapshot = new LinkedHashMap<>();
    snapshot.put("uptimeSeconds", uptimeSeconds());
    snapshot.put("counters", counters);
    snapshot.put(
        "averageRequestDurationMs", average(totalRequestDurationMs.sum(), httpRequestsTotal.sum()));
    snapshot.put(
        "averageAiCallDurationMs", average(aiCallDurationMs.sum(), aiCallCountForAverage.sum()));
    snapshot.put("auditWriteFailures", auditWriteFailures.sum());
    snapshot.put("timestamp", Instant.now().toString());
    return snapshot;
  }

  private long average(long totalMs, long count) {
    return count <= 0 ? 0 : totalMs / count;
  }
}
