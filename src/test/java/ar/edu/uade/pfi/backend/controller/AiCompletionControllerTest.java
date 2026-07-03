package ar.edu.uade.pfi.backend.controller;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ar.edu.uade.pfi.backend.client.AiServiceOperations;
import ar.edu.uade.pfi.backend.dto.PipelineRunRequestDto;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class AiCompletionControllerTest {
    @Test
    void completionIncludesGovernanceAndRoadmap() {
        Map<String, Object> response = new AiCompletionController(new FakeAi()).completion();

        assertEquals("mvp_completion_ready", response.get("status"));
        assertEquals(true, response.get("humanReviewRequired"));
        assertTrue(response.containsKey("roadmap"));
        assertTrue(response.containsKey("reports"));
    }

    static class FakeAi implements AiServiceOperations {
        public Map<String, Object> health() { return Map.of("status", "ok"); }
        public Map<String, Object> readiness() { return Map.of("readyForDemo", true, "mvpCompletion", Map.of("completionPercent", 80)); }
        public Object models() { return Map.of("status", "ok"); }
        public Map<String, Object> verifyModels() { return Map.of("status", "ok"); }
        public Map<String, Object> warmup() { return Map.of("status", "ok"); }
        public Map<String, Object> runPipeline(PipelineRunRequestDto request) { return Map.of("runId", "run-test"); }
        public Map<String, Object> getAgentReport(String runId) { return Map.of("runId", runId); }
        public Map<String, Object> getAgentReportSummary(String runId) { return Map.of("runId", runId); }
        public Map<String, Object> getRecentAgentReports(int limit) { return Map.of("count", 1, "items", List.of()); }
    }
}
