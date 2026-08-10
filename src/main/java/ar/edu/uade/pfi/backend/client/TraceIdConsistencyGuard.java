package ar.edu.uade.pfi.backend.client;

import ar.edu.uade.pfi.backend.client.exception.AiMultiplanarContractViolationException;

/**
 * Guards against the v2 request's body.traceId ever drifting from the X-Trace-Id header sent to the
 * AI Module. Both are sourced from the same MDC value in normal operation, so a mismatch here
 * indicates a code regression, not user input — hence the hard contract-violation failure rather
 * than silently picking one value.
 */
final class TraceIdConsistencyGuard {
  private TraceIdConsistencyGuard() {}

  static void require(String bodyTraceId, String headerTraceId) {
    if (bodyTraceId == null || headerTraceId == null) return;
    if (!bodyTraceId.equals(headerTraceId)) {
      throw new AiMultiplanarContractViolationException(
          "traceId del body v2 ("
              + bodyTraceId
              + ") no coincide con el header X-Trace-Id ("
              + headerTraceId
              + ")");
    }
  }
}
