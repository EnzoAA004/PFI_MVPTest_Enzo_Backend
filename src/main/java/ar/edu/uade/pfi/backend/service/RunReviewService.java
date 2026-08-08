package ar.edu.uade.pfi.backend.service;

import ar.edu.uade.pfi.backend.domain.DomainAuditEvent;
import ar.edu.uade.pfi.backend.domain.MeasurementCorrection;
import ar.edu.uade.pfi.backend.domain.RunReview;
import ar.edu.uade.pfi.backend.dto.MeasurementCorrectionDto;
import ar.edu.uade.pfi.backend.dto.RunReviewRequestDto;
import ar.edu.uade.pfi.backend.dto.RunReviewResponseDto;
import ar.edu.uade.pfi.backend.repository.StudyRepository;
import ar.edu.uade.pfi.backend.service.exceptions.DatabaseUnavailableException;
import ar.edu.uade.pfi.backend.service.exceptions.RunReviewException;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Service;

@Service
public class RunReviewService {
  public static final Set<String> REVIEW_STATUSES =
      Set.of("pending", "accepted", "observed", "rejected", "edited");

  private final StudyRepository repository;
  private final Clock clock;
  private final OperationalMetricsService metrics;

  @Autowired
  public RunReviewService(StudyRepository repository, @Nullable OperationalMetricsService metrics) {
    this(repository, Clock.systemUTC(), metrics);
  }

  public RunReviewService(StudyRepository repository) {
    this(repository, Clock.systemUTC(), null);
  }

  RunReviewService(StudyRepository repository, Clock clock) {
    this(repository, clock, null);
  }

  RunReviewService(StudyRepository repository, Clock clock, OperationalMetricsService metrics) {
    this.repository = repository;
    this.clock = clock;
    this.metrics = metrics;
  }

  public RunReviewResponseDto saveReview(String multiplanarRunId, RunReviewRequestDto request) {
    String status = normalizeStatus(request.reviewStatus());
    String reviewer = request.reviewer() == null ? "" : request.reviewer().trim();
    String comments = request.comments() == null ? "" : request.comments().trim();
    validateDecision(status, reviewer, comments);
    try {
      var run =
          repository
              .findRunByMultiplanarRunId(multiplanarRunId)
              .orElseThrow(
                  () ->
                      new RunReviewException(
                          HttpStatus.NOT_FOUND, "RUN_NOT_FOUND", "Run no encontrado."));
      Instant now = clock.instant();
      Instant reviewedAt = "pending".equals(status) ? null : now;
      RunReview review =
          repository.saveReview(
              multiplanarRunId,
              status,
              reviewer,
              reviewedAt,
              comments,
              corrections(run.id(), request.corrections(), now),
              auditEvent(
                  multiplanarRunId, run.traceId(), reviewer, status, request.corrections(), now));
      if (metrics != null) metrics.incrementReviewsSaved();
      return toResponse(review);
    } catch (RunReviewException ex) {
      throw ex;
    } catch (IllegalArgumentException ex) {
      if ("run_not_found".equals(ex.getMessage())) {
        throw new RunReviewException(HttpStatus.NOT_FOUND, "RUN_NOT_FOUND", "Run no encontrado.");
      }
      throw ex;
    } catch (IllegalStateException ex) {
      throw new DatabaseUnavailableException("Base de datos no disponible para guardar revision.");
    }
  }

  public RunReviewResponseDto findReview(String multiplanarRunId) {
    try {
      return repository
          .findReviewByMultiplanarRunId(multiplanarRunId)
          .map(this::toResponse)
          .orElseThrow(
              () ->
                  new RunReviewException(
                      HttpStatus.NOT_FOUND, "RUN_NOT_FOUND", "Run no encontrado."));
    } catch (RunReviewException ex) {
      throw ex;
    } catch (IllegalStateException ex) {
      throw new DatabaseUnavailableException(
          "Base de datos no disponible para consultar revision.");
    }
  }

  private String normalizeStatus(String status) {
    String normalized = status == null ? "" : status.trim().toLowerCase();
    if ("pendiente".equals(normalized)) normalized = "pending";
    if ("aceptado".equals(normalized)) normalized = "accepted";
    if ("observado".equals(normalized)) normalized = "observed";
    if ("descartado".equals(normalized) || "rechazado".equals(normalized)) normalized = "rejected";
    if ("editado".equals(normalized)) normalized = "edited";
    if (!REVIEW_STATUSES.contains(normalized)) {
      throw new RunReviewException(
          HttpStatus.BAD_REQUEST, "INVALID_REVIEW_STATUS", "reviewStatus invalido.");
    }
    return normalized;
  }

  private void validateDecision(String status, String reviewer, String comments) {
    if ("pending".equals(status)) return;
    if (reviewer.isBlank()) {
      throw new RunReviewException(
          HttpStatus.BAD_REQUEST, "REVIEWER_REQUIRED", "Reviewer es obligatorio.");
    }
    if (("observed".equals(status) || "rejected".equals(status)) && comments.length() < 5) {
      throw new RunReviewException(
          HttpStatus.BAD_REQUEST,
          "REVIEW_COMMENT_REQUIRED",
          "El estado requiere un comentario profesional descriptivo.");
    }
  }

  private List<MeasurementCorrection> corrections(
      String runId, List<MeasurementCorrectionDto> corrections, Instant createdAt) {
    if (corrections == null) return List.of();
    return corrections.stream()
        .map(
            correction ->
                new MeasurementCorrection(
                    UUID.randomUUID().toString(),
                    runId,
                    correction.measurementId() == null ? "" : correction.measurementId(),
                    correction.label() == null ? "" : correction.label(),
                    correction.beforeValue() == null ? Map.of() : correction.beforeValue(),
                    correction.afterValue() == null ? Map.of() : correction.afterValue(),
                    correction.comment() == null ? "" : correction.comment(),
                    createdAt))
        .toList();
  }

  private DomainAuditEvent auditEvent(
      String multiplanarRunId,
      String traceId,
      String reviewer,
      String status,
      List<MeasurementCorrectionDto> corrections,
      Instant now) {
    return new DomainAuditEvent(
        UUID.randomUUID().toString(),
        reviewer.isBlank() ? "backend" : reviewer,
        "review.updated",
        multiplanarRunId,
        traceId,
        now,
        Map.of(
            "reviewStatus",
            status,
            "correctionCount",
            corrections == null ? 0 : corrections.size()));
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
            .map(
                correction ->
                    new MeasurementCorrectionDto(
                        correction.measurementId(),
                        correction.label(),
                        correction.beforeValue(),
                        correction.afterValue(),
                        correction.comment()))
            .toList());
  }
}
