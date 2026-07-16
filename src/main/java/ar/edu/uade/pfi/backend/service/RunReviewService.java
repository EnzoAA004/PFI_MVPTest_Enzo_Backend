package ar.edu.uade.pfi.backend.service;

import ar.edu.uade.pfi.backend.domain.MeasurementCorrection;
import ar.edu.uade.pfi.backend.domain.RunReview;
import ar.edu.uade.pfi.backend.dto.MeasurementCorrectionDto;
import ar.edu.uade.pfi.backend.dto.RunReviewRequestDto;
import ar.edu.uade.pfi.backend.dto.RunReviewResponseDto;
import ar.edu.uade.pfi.backend.repository.StudyRepository;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

@Service
public class RunReviewService {
    public static final Set<String> REVIEW_STATUSES = Set.of("pending", "accepted", "observed", "rejected", "edited");

    private final StudyRepository repository;
    private final Clock clock;

    public RunReviewService(StudyRepository repository) {
        this(repository, Clock.systemUTC());
    }

    RunReviewService(StudyRepository repository, Clock clock) {
        this.repository = repository;
        this.clock = clock;
    }

    public RunReviewResponseDto saveReview(String multiplanarRunId, RunReviewRequestDto request) {
        if (repository.findRunByMultiplanarRunId(multiplanarRunId).isEmpty()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Run no encontrado.");
        }
        String status = normalizeStatus(request.reviewStatus());
        String reviewer = request.reviewer() == null ? "" : request.reviewer().trim();
        if (reviewer.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Reviewer es obligatorio.");
        }
        Instant reviewedAt = clock.instant();
        RunReview review = repository.saveReview(
            multiplanarRunId,
            status,
            reviewer,
            reviewedAt,
            request.comments() == null ? "" : request.comments(),
            corrections(multiplanarRunId, request.corrections(), reviewedAt)
        );
        return toResponse(review);
    }

    public RunReviewResponseDto findReview(String multiplanarRunId) {
        return repository.findReviewByMultiplanarRunId(multiplanarRunId)
            .map(this::toResponse)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Run no encontrado."));
    }

    private String normalizeStatus(String status) {
        String normalized = status == null ? "" : status.trim().toLowerCase();
        if ("aceptado".equals(normalized)) normalized = "accepted";
        if ("observado".equals(normalized)) normalized = "observed";
        if ("rechazado".equals(normalized)) normalized = "rejected";
        if (!REVIEW_STATUSES.contains(normalized) || "pending".equals(normalized)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "reviewStatus invalido.");
        }
        return normalized;
    }

    private List<MeasurementCorrection> corrections(String runId, List<MeasurementCorrectionDto> corrections, Instant createdAt) {
        if (corrections == null) return List.of();
        return corrections.stream()
            .map(correction -> new MeasurementCorrection(
                UUID.randomUUID().toString(),
                runId,
                correction.measurementId() == null ? "" : correction.measurementId(),
                correction.label() == null ? "" : correction.label(),
                correction.beforeValue() == null ? Map.of() : correction.beforeValue(),
                correction.afterValue() == null ? Map.of() : correction.afterValue(),
                correction.comment() == null ? "" : correction.comment(),
                createdAt
            ))
            .toList();
    }

    private RunReviewResponseDto toResponse(RunReview review) {
        return new RunReviewResponseDto(
            review.multiplanarRunId(),
            review.traceId(),
            review.reviewStatus(),
            review.reviewer(),
            review.reviewedAt(),
            review.comments(),
            review.corrections().stream()
                .map(correction -> new MeasurementCorrectionDto(
                    correction.measurementId(),
                    correction.label(),
                    correction.beforeValue(),
                    correction.afterValue(),
                    correction.comment()
                ))
                .toList()
        );
    }
}
