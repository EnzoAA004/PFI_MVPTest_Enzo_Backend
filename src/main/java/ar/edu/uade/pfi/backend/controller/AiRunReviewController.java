package ar.edu.uade.pfi.backend.controller;

import ar.edu.uade.pfi.backend.auth.RoleAuthorizationService;
import ar.edu.uade.pfi.backend.dto.RunReviewRequestDto;
import ar.edu.uade.pfi.backend.dto.RunReviewResponseDto;
import ar.edu.uade.pfi.backend.service.AuditService;
import ar.edu.uade.pfi.backend.service.RunReviewService;
import jakarta.servlet.http.HttpServletRequest;
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
    private final RoleAuthorizationService authorizationService;

    public AiRunReviewController(RunReviewService service) {
        this(service, null, null);
    }

    public AiRunReviewController(RunReviewService service, AuditService auditService) {
        this(service, auditService, null);
    }

    @Autowired
    public AiRunReviewController(RunReviewService service, AuditService auditService, RoleAuthorizationService authorizationService) {
        this.service = service;
        this.auditService = auditService;
        this.authorizationService = authorizationService;
    }

    @PostMapping("/{multiplanarRunId}/review")
    public RunReviewResponseDto createReview(
        @PathVariable String multiplanarRunId,
        @Valid @RequestBody RunReviewRequestDto request,
        HttpServletRequest httpRequest
    ) {
        requireProfessional(multiplanarRunId, httpRequest);
        return saveAndAudit(multiplanarRunId, request);
    }

    @PutMapping("/{multiplanarRunId}/review")
    public RunReviewResponseDto updateReview(
        @PathVariable String multiplanarRunId,
        @Valid @RequestBody RunReviewRequestDto request,
        HttpServletRequest httpRequest
    ) {
        requireProfessional(multiplanarRunId, httpRequest);
        return saveAndAudit(multiplanarRunId, request);
    }

    @GetMapping("/{multiplanarRunId}/review")
    public RunReviewResponseDto getReview(@PathVariable String multiplanarRunId, HttpServletRequest httpRequest) {
        requireProfessional(multiplanarRunId, httpRequest);
        return service.findReview(multiplanarRunId);
    }

    private void requireProfessional(String multiplanarRunId, HttpServletRequest request) {
        if (authorizationService != null) {
            authorizationService.requireProfessional(request, multiplanarRunId);
        }
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
