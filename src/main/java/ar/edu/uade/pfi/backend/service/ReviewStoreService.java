package ar.edu.uade.pfi.backend.service;

import ar.edu.uade.pfi.backend.dto.AuditEventDto;
import ar.edu.uade.pfi.backend.dto.AuditEventRequestDto;
import ar.edu.uade.pfi.backend.dto.MeasurementBatchDto;
import ar.edu.uade.pfi.backend.dto.MeasurementSaveDto;
import ar.edu.uade.pfi.backend.dto.ReviewSnapshotDto;
import ar.edu.uade.pfi.backend.dto.ReviewStatusDto;
import ar.edu.uade.pfi.backend.dto.ReviewUpdateRequestDto;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

@Service
public class ReviewStoreService {
    private static final Set<String> ALLOWED_STATUSES = Set.of("pendiente", "aceptado", "observado", "descartado");
    private final Map<String, ReviewStatusDto> reviews = new ConcurrentHashMap<>();
    private final Map<String, List<MeasurementSaveDto>> measurementsByRunId = new ConcurrentHashMap<>();
    private final List<AuditEventDto> auditTrail = new ArrayList<>();
    private final PostgresReviewStoreService postgres;

    public ReviewStoreService(PostgresReviewStoreService postgres) {
        this.postgres = postgres;
    }

    public ReviewStatusDto findOrDefault(String runId) {
        return reviews.getOrDefault(runId, new ReviewStatusDto(runId, "pendiente", "", "", Instant.now()));
    }

    public ReviewSnapshotDto snapshot() {
        ReviewSnapshotDto persisted = postgres.enabled() ? postgres.snapshot() : null;
        if (persisted != null) return persisted;
        return new ReviewSnapshotDto(
            reviews.values().stream().sorted(Comparator.comparing(ReviewStatusDto::updatedAt).reversed()).toList(),
            Map.copyOf(measurementsByRunId),
            auditTrail.stream().sorted(Comparator.comparing(AuditEventDto::timestamp).reversed()).limit(100).toList()
        );
    }

    public List<MeasurementSaveDto> findMeasurements(String runId) {
        if (postgres.enabled()) {
            List<MeasurementSaveDto> persisted = postgres.findMeasurements(runId);
            if (!persisted.isEmpty()) return persisted;
        }
        return measurementsByRunId.getOrDefault(runId, List.of());
    }

    public List<MeasurementSaveDto> saveMeasurements(String runId, MeasurementBatchDto request) {
        List<MeasurementSaveDto> next = request.measurements() == null ? List.of() : List.copyOf(request.measurements());
        measurementsByRunId.put(runId, next);
        postgres.saveMeasurements(runId, next);
        appendAudit(new AuditEventRequestDto(
            defaultString(request.reviewer(), "Reviewer"),
            "measurements_updated",
            defaultString(request.detail(), "Mediciones revisadas para runId=" + runId)
        ));
        return next;
    }

    public AuditEventDto appendAudit(AuditEventRequestDto request) {
        AuditEventDto event = new AuditEventDto(
            "audit-" + UUID.randomUUID(),
            Instant.now(),
            defaultString(request.reviewer(), "System"),
            defaultString(request.action(), "event"),
            defaultString(request.detail(), "")
        );
        auditTrail.add(event);
        if (auditTrail.size() > 200) auditTrail.remove(0);
        postgres.appendAudit(event);
        return event;
    }

    public List<AuditEventDto> auditTrail() {
        if (postgres.enabled()) {
            List<AuditEventDto> persisted = postgres.findAuditTrail();
            if (!persisted.isEmpty()) return persisted;
        }
        return auditTrail.stream().sorted(Comparator.comparing(AuditEventDto::timestamp).reversed()).limit(100).toList();
    }

    public ReviewStatusDto updateReview(String runId, ReviewUpdateRequestDto request) {
        String normalizedStatus = request.status().trim().toLowerCase();
        if (!ALLOWED_STATUSES.contains(normalizedStatus)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid review status");
        }
        ReviewStatusDto review = new ReviewStatusDto(
            runId,
            normalizedStatus,
            request.notes() == null ? "" : request.notes(),
            request.reviewer() == null ? "" : request.reviewer(),
            Instant.now()
        );
        reviews.put(runId, review);
        postgres.saveReview(review);
        appendAudit(new AuditEventRequestDto(
            review.reviewer(),
            "review_" + normalizedStatus,
            "Revision " + normalizedStatus + " guardada para runId=" + runId
        ));
        return review;
    }

    private String defaultString(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }
}
