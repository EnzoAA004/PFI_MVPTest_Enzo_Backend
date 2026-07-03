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

class AiEvaluationEmptyReportsTest {
    @Test
    void summaryIsSafeWithoutReports() throws Exception {
        MockMvc mvc = MockMvcBuilders.standaloneSetup(new AiEvaluationController(new EmptyAi())).build();

        mvc.perform(get("/api/ai/evaluation/summary"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.hasReports").value(false))
            .andExpect(jsonPath("$.latestRunId").value(""));
    }

    static class EmptyAi implements AiServiceOperations {
        public Map<String, Object> health() { return Map.of("status", "ok"); }
        public Map<String, Object> readiness() { return Map.of("status", "contract_ready"); }
        public Object models() { return Map.of("status", "ok"); }
        public Map<String, Object> verifyModels() { return Map.of("status", "ok"); }
        public Map<String, Object> warmup() { return Map.of("status", "ok"); }
        public Map<String, Object> runPipeline(PipelineRunRequestDto request) { return Map.of("runId", "run-test"); }
        public Map<String, Object> getAgentReport(String runId) { return Map.of("runId", runId); }
        public Map<String, Object> getAgentReportSummary(String runId) { return Map.of("runId", runId); }
        public Map<String, Object> getRecentAgentReports(int limit) { return Map.of("count", 0, "items", List.of()); }
    }
}
