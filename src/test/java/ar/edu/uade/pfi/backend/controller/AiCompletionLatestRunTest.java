package ar.edu.uade.pfi.backend.controller;

import static org.junit.jupiter.api.Assertions.assertEquals;

import ar.edu.uade.pfi.backend.client.AiServiceOperations;
import ar.edu.uade.pfi.backend.dto.PipelineRunRequestDto;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class AiCompletionLatestRunTest {
    @Test
    void completionIncludesLatestRunId() {
        Map<String, Object> response = new AiCompletionController(new FakeAi()).completion();

        assertEquals("run-123", response.get("latestRunId"));
    }

    static class FakeAi implements AiServiceOperations {
        public Map<String, Object> health() { return Map.of("status", "ok"); }
        public Map<String, Object> readiness() { return Map.of("readyForDemo", true); }
        public Object models() { return Map.of("status", "ok"); }
        public Map<String, Object> verifyModels() { return Map.of("status", "ok"); }
        public Map<String, Object> warmup() { return Map.of("status", "ok"); }
        public Map<String, Object> runPipeline(PipelineRunRequestDto request) { return Map.of("runId", "run-123"); }
        public Map<String, Object> getAgentReport(String runId) { return Map.of("runId", runId); }
        public Map<String, Object> getAgentReportSummary(String runId) { return Map.of("runId", runId); }
        public Map<String, Object> getRecentAgentReports(int limit) { return Map.of("count", 1, "items", List.of(Map.of("runId", "run-123"))); }
    }
}
