package ar.edu.uade.pfi.backend.service;

import ar.edu.uade.pfi.backend.domain.DomainAuditEvent;
import ar.edu.uade.pfi.backend.domain.MeasurementCorrection;
import ar.edu.uade.pfi.backend.domain.RunReview;
import ar.edu.uade.pfi.backend.domain.StudyRun;
import ar.edu.uade.pfi.backend.dto.MeasurementCorrectionDto;
import ar.edu.uade.pfi.backend.dto.RunReviewRequestDto;
import ar.edu.uade.pfi.backend.dto.RunReviewResponseDto;
import ar.edu.uade.pfi.backend.repository.StudyRepository;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Service;

@Service
public class RunReviewService {
    public static final Set<String> REVIEW_STATUSES = Set.of("pending", "accepted", "observed", "rejected", "edited");

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
            var run = repository.findRunByMultiplanarRunId(multiplanarRunId)
                .orElseThrow(() -> new RunReviewException(HttpStatus.NOT_FOUND, "RUN_NOT_FOUND", "Run no encontrado."));
            Instant now = clock.instant();
            Instant reviewedAt = "pending".equals(status) ? null : now;
            RunReview review = repository.saveReview(
                multiplanarRunId,
                status,
                reviewer,
                reviewedAt,
                comments,
                corrections(run, request.corrections(), now),
                auditEvent(multiplanarRunId, run.traceId(), reviewer, status, request.corrections(), now)
            );
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
            return repository.findReviewByMultiplanarRunId(multiplanarRunId)
                .map(this::toResponse)
                .orElseThrow(() -> new RunReviewException(HttpStatus.NOT_FOUND, "RUN_NOT_FOUND", "Run no encontrado."));
        } catch (RunReviewException ex) {
            throw ex;
        } catch (IllegalStateException ex) {
            throw new DatabaseUnavailableException("Base de datos no disponible para consultar revision.");
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
            throw new RunReviewException(HttpStatus.BAD_REQUEST, "INVALID_REVIEW_STATUS", "reviewStatus invalido.");
        }
        return normalized;
    }

    private void validateDecision(String status, String reviewer, String comments) {
        if ("pending".equals(status)) return;
        if (reviewer.isBlank()) {
            throw new RunReviewException(HttpStatus.BAD_REQUEST, "REVIEWER_REQUIRED", "Reviewer es obligatorio.");
        }
        if (("observed".equals(status) || "rejected".equals(status)) && comments.length() < 5) {
            throw new RunReviewException(HttpStatus.BAD_REQUEST, "REVIEW_COMMENT_REQUIRED", "El estado requiere un comentario profesional descriptivo.");
        }
    }

    private List<MeasurementCorrection> corrections(StudyRun run, List<MeasurementCorrectionDto> corrections, Instant createdAt) {
        if (corrections == null) return List.of();
        ResultIndex index = resultIndex(run.metricsSnapshot());
        return corrections.stream()
            .map(correction -> correction(run.id(), correction, index, createdAt))
            .toList();
    }

    private MeasurementCorrection correction(String runId, MeasurementCorrectionDto correction, ResultIndex index, Instant createdAt) {
        String measurementId = correction.measurementId() == null ? "" : correction.measurementId().trim();
        if (measurementId.isBlank()) {
            throw new RunReviewException(HttpStatus.BAD_REQUEST, "INVALID_MEASUREMENT_CORRECTION", "measurementId invalido.");
        }
        MeasurementRef ref = index.measurements().get(measurementId);
        if (ref == null) {
            throw new RunReviewException(HttpStatus.BAD_REQUEST, "INVALID_MEASUREMENT_CORRECTION", "La medicion no existe para esta corrida.");
        }
        Map<String, Object> before = correction.beforeValue() == null ? new LinkedHashMap<>() : new LinkedHashMap<>(correction.beforeValue());
        Map<String, Object> after = correction.afterValue() == null ? new LinkedHashMap<>() : new LinkedHashMap<>(correction.afterValue());
        String requestedPlane = firstText(after.get("plane"), before.get("plane"));
        if (!requestedPlane.isBlank() && !requestedPlane.equals(ref.plane())) {
            throw new RunReviewException(HttpStatus.BAD_REQUEST, "INVALID_MEASUREMENT_CORRECTION", "La medicion no corresponde al plano indicado.");
        }
        Integer requestedSlice = firstInt(after.get("sliceIndex"), before.get("sliceIndex"));
        Integer sliceIndex = requestedSlice == null ? ref.sliceIndex() : requestedSlice;
        if (ref.sliceIndex() != null && sliceIndex != null && !ref.sliceIndex().equals(sliceIndex)) {
            throw new RunReviewException(HttpStatus.BAD_REQUEST, "INVALID_MEASUREMENT_CORRECTION", "La medicion no corresponde al slice indicado.");
        }
        before.putIfAbsent("plane", ref.plane());
        after.putIfAbsent("plane", ref.plane());
        if (sliceIndex != null) {
            before.putIfAbsent("sliceIndex", sliceIndex);
            after.putIfAbsent("sliceIndex", sliceIndex);
        }
        return new MeasurementCorrection(
            UUID.randomUUID().toString(),
            runId,
            measurementId,
            correction.label() == null ? "" : correction.label(),
            before,
            after,
            correction.comment() == null ? "" : correction.comment(),
            createdAt
        );
    }

    @SuppressWarnings("unchecked")
    private ResultIndex resultIndex(Map<String, Object> snapshot) {
        Map<String, MeasurementRef> measurements = new LinkedHashMap<>();
        Object planesNode = snapshot.get("planes");
        if (!(planesNode instanceof Map<?, ?> planes)) return new ResultIndex(measurements);
        for (Map.Entry<?, ?> entry : planes.entrySet()) {
            String plane = String.valueOf(entry.getKey());
            if (!(entry.getValue() instanceof Map<?, ?> rawPlane)) continue;
            Map<String, Object> planeMap = new LinkedHashMap<>((Map<String, Object>) rawPlane);
            Map<String, Integer> sliceByMeasurementId = sliceByMeasurementId(planeMap);
            for (Map<String, Object> measurement : mapList(planeMap.get("measurements"))) {
                String id = text(measurement.get("id"));
                if (id.isBlank()) continue;
                measurements.put(id, new MeasurementRef(plane, Optional.ofNullable(intValue(measurement.get("sliceIndex"))).orElse(sliceByMeasurementId.get(id))));
            }
        }
        return new ResultIndex(measurements);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Integer> sliceByMeasurementId(Map<String, Object> planeMap) {
        Map<String, Integer> byMeasurement = new LinkedHashMap<>();
        Object input = planeMap.get("input");
        if (!(input instanceof Map<?, ?> inputMap)) return byMeasurement;
        Object slices = inputMap.get("slices");
        if (!(slices instanceof List<?> list)) return byMeasurement;
        for (Object value : list) {
            if (!(value instanceof Map<?, ?> rawSlice)) continue;
            Map<String, Object> slice = new LinkedHashMap<>((Map<String, Object>) rawSlice);
            Integer index = intValue(slice.get("index"));
            if (index == null) continue;
            for (String measurementId : stringList(slice.get("measurementIds"))) {
                byMeasurement.putIfAbsent(measurementId, index);
            }
        }
        return byMeasurement;
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> mapList(Object value) {
        if (!(value instanceof List<?> list)) return List.of();
        List<Map<String, Object>> result = new ArrayList<>();
        for (Object item : list) {
            if (item instanceof Map<?, ?> map) result.add(new LinkedHashMap<>((Map<String, Object>) map));
        }
        return result;
    }

    private List<String> stringList(Object value) {
        if (!(value instanceof List<?> list)) return List.of();
        List<String> result = new ArrayList<>();
        for (Object item : list) {
            if (item != null && !String.valueOf(item).isBlank()) result.add(String.valueOf(item));
        }
        return result;
    }

    private DomainAuditEvent auditEvent(String multiplanarRunId, String traceId, String reviewer, String status, List<MeasurementCorrectionDto> corrections, Instant now) {
        return new DomainAuditEvent(
            UUID.randomUUID().toString(),
            reviewer.isBlank() ? "backend" : reviewer,
            "review.updated",
            multiplanarRunId,
            traceId,
            now,
            Map.of(
                "reviewStatus", status,
                "correctionCount", corrections == null ? 0 : corrections.size()
            )
        );
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

    private String firstText(Object first, Object second) {
        String value = text(first);
        return value.isBlank() ? text(second) : value;
    }

    private Integer firstInt(Object first, Object second) {
        Integer value = intValue(first);
        return value == null ? intValue(second) : value;
    }

    private Integer intValue(Object value) {
        if (value instanceof Number number) return number.intValue();
        try {
            return value == null ? null : Integer.parseInt(String.valueOf(value));
        } catch (NumberFormatException ex) {
            return null;
        }
    }

    private String text(Object value) {
        return value == null ? "" : String.valueOf(value).trim();
    }

    private record ResultIndex(Map<String, MeasurementRef> measurements) {}

    private record MeasurementRef(String plane, Integer sliceIndex) {}
}
