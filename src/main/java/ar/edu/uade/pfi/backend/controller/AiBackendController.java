package ar.edu.uade.pfi.backend.controller;

import ar.edu.uade.pfi.backend.dto.PipelineRunRequestDto;
import ar.edu.uade.pfi.backend.dto.ReviewStatusDto;
import ar.edu.uade.pfi.backend.dto.ReviewUpdateRequestDto;
import ar.edu.uade.pfi.backend.service.AiBackendService;
import jakarta.validation.Valid;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/ai")
public class AiBackendController {
    private final AiBackendService aiBackendService;

    public AiBackendController(AiBackendService aiBackendService) {
        this.aiBackendService = aiBackendService;
    }

    @GetMapping("/health")
    public Map<String, Object> health() {
        return aiBackendService.health();
    }

    @GetMapping("/models")
    public Object models() {
        return aiBackendService.models();
    }

    @PostMapping("/pipeline/run")
    public Map<String, Object> runPipeline(@Valid @RequestBody PipelineRunRequestDto request) {
        return aiBackendService.runPipeline(request);
    }

    @GetMapping("/agent/report/{runId}")
    public Map<String, Object> getAgentReport(@PathVariable String runId) {
        return aiBackendService.getAgentReport(runId);
    }

    @PatchMapping("/review/{runId}")
    public ReviewStatusDto updateReview(
        @PathVariable String runId,
        @Valid @RequestBody ReviewUpdateRequestDto request
    ) {
        return aiBackendService.updateReview(runId, request);
    }
}
