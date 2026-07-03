package ar.edu.uade.pfi.backend.controller;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import ar.edu.uade.pfi.backend.client.AiServiceOperations;
import ar.edu.uade.pfi.backend.dto.PipelineRunRequestDto;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class AiEvaluationEvidenceTest {
    @Test
    void evidenceEndpointReturnsLatestRunAndGovernance() throws Exception {
        MockMvc mvc = MockMvcBuilders.standaloneSetup(new AiEvaluationController(new FakeAi())).build();

        mvc.perform(get("/api/ai/evaluation/evidence"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.status").value("evaluation_evidence_ready"))
            .andExpect(jsonPath("$.latestRunId").value("run-789"))
            .andExpect(jsonPath("$.hasReports").value(true))
            .andExpect(jsonPath("$.humanReviewRequired").value(true));
    }

    static class FakeAi implements AiServiceOperations {
        public Map<String, Object> health() { return Map.of("status", "ok"); }
        public Map<String, Object> readiness() { return Map.of("status", "contract_ready"); }
        public Object models() { return Map.of("status", "ok"); }
        public Map<String, Object> verifyModels() { return Map.of("status", "ok"); }
        public Map<String, Object> warmup() { return Map.of("status", "ok"); }
        public Map<String, Object> runPipeline(PipelineRunRequestDto request) { return Map.of("runId", "run-789"); }
        public Map<String, Object> getAgentReport(String runId) { return Map.of("runId", runId); }
        public Map<String, Object> getAgentReportSummary(String runId) { return Map.of("runId", runId); }
        public Map<String, Object> getRecentAgentReports(int limit) { return Map.of("count", 1, "items", List.of(Map.of("runId", "run-789"))); }
    }
}
