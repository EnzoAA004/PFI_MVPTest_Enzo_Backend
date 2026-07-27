package ar.edu.uade.pfi.backend.controller;

import ar.edu.uade.pfi.backend.dto.AiInputResponseDto;
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
import ar.edu.uade.pfi.backend.auth.RoleAuthorizationService;
import ar.edu.uade.pfi.backend.service.AiBackendService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/api/ai")
public class AiBackendController {
    private final AiBackendService aiBackendService;
    private final RoleAuthorizationService authorizationService;

    @Autowired
    public AiBackendController(AiBackendService aiBackendService, RoleAuthorizationService authorizationService) {
        this.aiBackendService = aiBackendService;
        this.authorizationService = authorizationService;
    }

    /**
     * No longer publicly reachable (see AuthFilter.PUBLIC_LIVENESS_PATHS): any
     * authenticated, non-pending user (professional or ADMIN) may call this — it is
     * gated purely by AuthFilter's default "authenticated" requirement, no extra role
     * check needed here.
     */
    @GetMapping("/health")
    public Map<String, Object> health() {
        return aiBackendService.health();
    }

    /**
     * Technical diagnostic with no documented professional consumer today — ADMIN-only
     * per P10-A.1 (was previously reachable by any authenticated professional).
     */
    @GetMapping("/readiness")
    public Map<String, Object> readiness(HttpServletRequest request) {
        authorizationService.requireAdmin(request, "ai.readiness");
        return aiBackendService.readiness();
    }

    /** Same as /health: gated purely by AuthFilter's default "authenticated" requirement. */
    @GetMapping("/models")
    public Object models() {
        return aiBackendService.models();
    }

    /**
     * Technical diagnostic with no documented professional consumer today — ADMIN-only
     * per P10-A.1 (was previously reachable by any authenticated professional).
     */
    @GetMapping("/models/verify")
    public Map<String, Object> verifyModels(HttpServletRequest request) {
        authorizationService.requireAdmin(request, "ai.models.verify");
        return aiBackendService.verifyModels();
    }

    @PostMapping("/pipeline/run")
    public Map<String, Object> runPipeline(@Valid @RequestBody PipelineRunRequestDto request) {
        return aiBackendService.runPipeline(request);
    }

    @PostMapping(value = "/inputs", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public AiInputResponseDto uploadInput(
        @RequestParam("file") MultipartFile file,
        @RequestParam String caseId,
        @RequestParam String plane
    ) {
        return aiBackendService.uploadInput(file, caseId, plane);
    }

    @GetMapping("/assets/{runId}/{plane}/{assetName}")
    public ResponseEntity<byte[]> getAsset(
        @PathVariable String runId,
        @PathVariable String plane,
        @PathVariable String assetName
    ) {
        return aiBackendService.getAsset(runId, plane, assetName);
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
    public AuditEventDto appendAudit(@RequestBody AuditEventRequestDto request, HttpServletRequest httpRequest) {
        authorizationService.requireAdmin(httpRequest, "audit.trail");
        return aiBackendService.appendAudit(request);
    }

    @GetMapping("/audit")
    public List<AuditEventDto> auditTrail(HttpServletRequest httpRequest) {
        authorizationService.requireAdmin(httpRequest, "audit.trail");
        return aiBackendService.auditTrail();
    }
}
