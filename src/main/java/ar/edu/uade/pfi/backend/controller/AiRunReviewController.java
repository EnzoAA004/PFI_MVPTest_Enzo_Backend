package ar.edu.uade.pfi.backend.controller;

import ar.edu.uade.pfi.backend.dto.RunReviewRequestDto;
import ar.edu.uade.pfi.backend.dto.RunReviewResponseDto;
import ar.edu.uade.pfi.backend.service.RunReviewService;
import jakarta.validation.Valid;
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

    public AiRunReviewController(RunReviewService service) {
        this.service = service;
    }

    @PostMapping("/{multiplanarRunId}/review")
    public RunReviewResponseDto createReview(@PathVariable String multiplanarRunId, @Valid @RequestBody RunReviewRequestDto request) {
        return service.saveReview(multiplanarRunId, request);
    }

    @PutMapping("/{multiplanarRunId}/review")
    public RunReviewResponseDto updateReview(@PathVariable String multiplanarRunId, @Valid @RequestBody RunReviewRequestDto request) {
        return service.saveReview(multiplanarRunId, request);
    }

    @GetMapping("/{multiplanarRunId}/review")
    public RunReviewResponseDto getReview(@PathVariable String multiplanarRunId) {
        return service.findReview(multiplanarRunId);
    }
}
