package ar.edu.uade.pfi.backend.client;

import ar.edu.uade.pfi.backend.dto.PipelineRunRequestDto;
import java.util.Map;

public interface AiServiceOperations {
    Map<String, Object> health();

    default Map<String, Object> readiness() {
        return health();
    }

    Object models();

    Map<String, Object> verifyModels();

    default Map<String, Object> syncModels(boolean force) {
        return Map.of("status", "models_sync_unavailable", "force", force, "humanReviewRequired", true, "notClinicalDiagnosis", true);
    }

    Map<String, Object> warmup();

    Map<String, Object> runPipeline(PipelineRunRequestDto request);

    Map<String, Object> getAgentReport(String runId);

    Map<String, Object> getAgentReportSummary(String runId);

    Map<String, Object> getRecentAgentReports(int limit);

    default Map<String, Object> getEvaluationSummary() {
        return getRecentAgentReports(100);
    }

    default Map<String, Object> getEvaluationEvidence() {
        return getEvaluationSummary();
    }

    default Map<String, Object> getMultiplanarContract() {
        return Map.of("status", "multiplanar_unavailable", "humanReviewRequired", true, "notClinicalDiagnosis", true);
    }
}
