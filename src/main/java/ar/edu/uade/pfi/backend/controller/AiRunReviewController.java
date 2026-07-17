package ar.edu.uade.pfi.backend.controller;

import ar.edu.uade.pfi.backend.dto.RunReviewRequestDto;
import ar.edu.uade.pfi.backend.dto.RunReviewResponseDto;
import ar.edu.uade.pfi.backend.service.AuditService;
import ar.edu.uade.pfi.backend.service.RunReviewService;
import jakarta.validation.Valid;
import java.util.Map;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/ai/runs")
public class AiRunReviewController {
    private final RunReviewService service;
    private final AuditService auditService;

    public AiRunReviewController(RunReviewService service) {
        this(service, null);
    }

    @Autowired
    public AiRunReviewController(RunReviewService service, AuditService auditService) {
        this.service = service;
        this.auditService = auditService;
    }

    @PostMapping("/{multiplanarRunId}/review")
    public RunReviewResponseDto createReview(@PathVariable String multiplanarRunId, @Valid @RequestBody RunReviewRequestDto request) {
        return saveAndAudit(multiplanarRunId, request);
    }

    @PutMapping("/{multiplanarRunId}/review")
    public RunReviewResponseDto updateReview(@PathVariable String multiplanarRunId, @Valid @RequestBody RunReviewRequestDto request) {
        return saveAndAudit(multiplanarRunId, request);
    }

    @GetMapping("/{multiplanarRunId}/review")
    public RunReviewResponseDto getReview(@PathVariable String multiplanarRunId) {
        return service.findReview(multiplanarRunId);
    }

    private RunReviewResponseDto saveAndAudit(String multiplanarRunId, RunReviewRequestDto request) {
        RunReviewResponseDto response = service.saveReview(multiplanarRunId, request);
        if (auditService != null) {
            auditService.record(response.reviewer(), "review.updated", multiplanarRunId, response.traceId(), Map.of(
                "reviewStatus", response.reviewStatus(),
                "correctionCount", response.corrections().size()
            ));
        }
        return response;
    }
}
