package ar.edu.uade.pfi.backend.client;

import ar.edu.uade.pfi.backend.domain.CanonicalMultiplanarRun;
import ar.edu.uade.pfi.backend.dto.AiInputResponseDto;
import ar.edu.uade.pfi.backend.dto.AiSubarticularPredictRequestDto;
import ar.edu.uade.pfi.backend.dto.MultiplanarRunRequestDto;
import ar.edu.uade.pfi.backend.dto.PipelineRunRequestDto;
import ar.edu.uade.pfi.backend.dto.SubarticularPredictionResponseDto;
import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.web.multipart.MultipartFile;

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

    default AiInputResponseDto uploadInput(MultipartFile file, String caseId, String plane) {
        throw new UnsupportedOperationException("input_upload_unavailable");
    }

    default Map<String, Object> uploadStudy(MultipartFile file, String caseId) {
        throw new UnsupportedOperationException("study_upload_unavailable");
    }

    default ResponseEntity<byte[]> getAsset(String runId, String plane, String assetName) {
        throw new UnsupportedOperationException("asset_proxy_unavailable");
    }

    /** A slice of a stored series, for the series of the study no model ran on. */
    default ResponseEntity<byte[]> getSeriesSlice(String inputId, int index) {
        throw new UnsupportedOperationException("series_slice_proxy_unavailable");
    }

    default CanonicalMultiplanarRun runMultiplanar(MultiplanarRunRequestDto request) {
        throw new UnsupportedOperationException("multiplanar_run_unavailable");
    }

    /**
     * Clasificacion subarticular sobre una coordenada marcada a mano. Es puntual: no
     * forma parte de una corrida y no persiste nada.
     */
    default SubarticularPredictionResponseDto predictSubarticular(AiSubarticularPredictRequestDto request) {
        throw new UnsupportedOperationException("subarticular_prediction_unavailable");
    }

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
