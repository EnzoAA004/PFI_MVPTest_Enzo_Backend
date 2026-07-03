package ar.edu.uade.pfi.backend.util;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class AiReportEvidenceTest {
    @Test
    void latestRunIdReturnsEmptyWhenReportsAreMissing() {
        Map<String, Object> reports = Map.of("count", 0, "items", List.of());

        assertFalse(AiReportEvidence.hasReports(reports));
        assertEquals("", AiReportEvidence.latestRunId(reports));
    }

    @Test
    void latestRunIdReturnsFirstReportRunId() {
        Map<String, Object> reports = Map.of("count", 1, "items", List.of(Map.of("runId", "run-456")));

        assertTrue(AiReportEvidence.hasReports(reports));
        assertEquals("run-456", AiReportEvidence.latestRunId(reports));
    }
}
