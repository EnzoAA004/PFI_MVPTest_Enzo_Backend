package ar.edu.uade.pfi.backend.client;

import ar.edu.uade.pfi.backend.dto.PipelineRunRequestDto;
import java.util.Map;

public interface AiServiceOperations {
    Map<String, Object> health();

    Object models();

    Map<String, Object> runPipeline(PipelineRunRequestDto request);

    Map<String, Object> getAgentReport(String runId);
}
