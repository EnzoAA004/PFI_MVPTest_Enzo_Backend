package ar.edu.uade.pfi.backend.controller;

import ar.edu.uade.pfi.backend.dto.AuditEventDto;
import ar.edu.uade.pfi.backend.dto.AuditEventRequestDto;
import ar.edu.uade.pfi.backend.dto.MeasurementBatchDto;
import ar.edu.uade.pfi.backend.dto.MeasurementSaveDto;
import ar.edu.uade.pfi.backend.dto.PipelineRunRequestDto;
import ar.edu.uade.pfi.backend.dto.ReviewExportRequestDto;
import ar.edu.uade.pfi.backend.dto.ReviewExportResponseDto;
import ar.edu.uade.pfi.backend.dto.ReviewSnapshotDto;
import ar.edu.uade.pfi.backend.dto.ReviewStatusDto;
import ar.edu.uade.pfi.backend.dto.ReviewUpdateRequestDto;
import ar.edu.uade.pfi.backend.service.AiBackendService;
import jakarta.validation.Valid;
import java.util.List;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
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

    @GetMapping("/models/verify")
    public Map<String, Object> verifyModels() {
        return aiBackendService.verifyModels();
    }

    @PostMapping("/pipeline/run")
    public Map<String, Object> runPipeline(@Valid @RequestBody PipelineRunRequestDto request) {
        return aiBackendService.runPipeline(request);
    }

    @GetMapping("/agent/reports")
    public Map<String, Object> getRecentAgentReports(@RequestParam(defaultValue = "20") int limit) {
        return aiBackendService.getRecentAgentReports(limit);
    }

    @GetMapping("/agent/report/{runId}/summary")
    public Map<String, Object> getAgentReportSummary(@PathVariable String runId) {
        return aiBackendService.getAgentReportSummary(runId);
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

    @GetMapping("/review/history")
    public ReviewSnapshotDto reviewHistory() {
        return aiBackendService.reviewHistory();
    }

    @GetMapping("/review/{runId}/measurements")
    public List<MeasurementSaveDto> getMeasurements(@PathVariable String runId) {
        return aiBackendService.getMeasurements(runId);
    }

    @PutMapping("/review/{runId}/measurements")
    public List<MeasurementSaveDto> saveMeasurements(
        @PathVariable String runId,
        @RequestBody MeasurementBatchDto request
    ) {
        return aiBackendService.saveMeasurements(runId, request);
    }

    @PostMapping("/review/{runId}/export")
    public ReviewExportResponseDto exportReview(
        @PathVariable String runId,
        @RequestBody ReviewExportRequestDto request
    ) {
        return aiBackendService.exportReview(runId, request);
    }

    @PostMapping("/audit")
    public AuditEventDto appendAudit(@RequestBody AuditEventRequestDto request) {
        return aiBackendService.appendAudit(request);
    }

    @GetMapping("/audit")
    public List<AuditEventDto> auditTrail() {
        return aiBackendService.auditTrail();
    }
}
