package ar.edu.uade.pfi.backend.client;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

import ar.edu.uade.pfi.backend.service.AiMultiplanarContractViolationException;
import org.junit.jupiter.api.Test;

class TraceIdConsistencyGuardTest {
  @Test
  void matchingTraceIdsDoNotThrow() {
    assertDoesNotThrow(() -> TraceIdConsistencyGuard.require("trace-1", "trace-1"));
  }

  @Test
  void mismatchedTraceIdsThrowContractViolation() {
    assertThrows(
        AiMultiplanarContractViolationException.class,
        () -> TraceIdConsistencyGuard.require("trace-1", "trace-2"));
  }

  @Test
  void nullValuesAreSkippedRatherThanFlaggedAsMismatch() {
    assertDoesNotThrow(() -> TraceIdConsistencyGuard.require(null, "trace-2"));
    assertDoesNotThrow(() -> TraceIdConsistencyGuard.require("trace-1", null));
  }
}
