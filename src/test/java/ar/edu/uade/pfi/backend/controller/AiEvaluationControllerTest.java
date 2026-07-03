package ar.edu.uade.pfi.backend.controller;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import ar.edu.uade.pfi.backend.client.AiServiceOperations;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class AiEvaluationControllerTest {
    @Test
    void contractReturnsEvaluationShape() throws Exception {
        MockMvc mvc = MockMvcBuilders.standaloneSetup(new AiEvaluationController(new FakeAi())).build();

        mvc.perform(get("/api/ai/evaluation/contract"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.status").value("evaluation_contract_ready"))
            .andExpect(jsonPath("$.metrics[0].key").value("dice"))
            .andExpect(jsonPath("$.metrics[0].category").value("segmentation"))
            .andExpect(jsonPath("$.requiredEvidence[0]").value("trace_id"))
            .andExpect(jsonPath("$.humanReviewRequired").value(true));
    }

    @Test
    void summaryReturnsReportCount() throws Exception {
        MockMvc mvc = MockMvcBuilders.standaloneSetup(new AiEvaluationController(new FakeAi())).build();

        mvc.perform(get("/api/ai/evaluation/summary"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.status").value("evaluation_summary_ready"))
            .andExpect(jsonPath("$.reportCount").value(1));
    }

    static class FakeAi implements AiServiceOperations {
        public Map<String, Object> health() { return Map.of("status", "ok"); }
        public Map<String, Object> readiness() { return Map.of("status", "contract_ready"); }
        public Object models() { return Map.of("status", "ok"); }
        public Map<String, Object> verifyModels() { return Map.of("status", "degraded_contract_mode"); }
        public Map<String, Object> warmup() { return Map.of("status", "ok"); }
        public Map<String, Object> runPipeline(ar.edu.uade.pfi.backend.dto.PipelineRunRequestDto request) { return Map.of("runId", "run-test"); }
        public Map<String, Object> getAgentReport(String runId) { return Map.of("runId", runId); }
        public Map<String, Object> getAgentReportSummary(String runId) { return Map.of("runId", runId); }
        public Map<String, Object> getRecentAgentReports(int limit) { return Map.of("count", 1, "items", List.of(Map.of("runId", "run-test"))); }
    }
}
