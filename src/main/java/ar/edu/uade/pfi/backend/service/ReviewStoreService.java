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

    private final Map<String, ReviewStatusDto> store = new ConcurrentHashMap<>();
    private final Map<String, List<MeasurementSaveDto>> measurementsByRunId = new ConcurrentHashMap<>();
    private final List<AuditEventDto> auditTrail = new ArrayList<>();

    public ReviewStatusDto findOrDefault(String runId) {
        return store.getOrDefault(runId, new ReviewStatusDto(runId, "pendiente", "", "", Instant.now()));
    }

    public ReviewSnapshotDto snapshot() {
        return new ReviewSnapshotDto(
            store.values().stream().sorted(Comparator.comparing(ReviewStatusDto::updatedAt).reversed()).toList(),
            Map.copyOf(measurementsByRunId),
            auditTrail.stream().sorted(Comparator.comparing(AuditEventDto::timestamp).reversed()).limit(100).toList()
        );
    }

    public List<MeasurementSaveDto> findMeasurements(String runId) {
        return measurementsByRunId.getOrDefault(runId, List.of());
    }

    public List<MeasurementSaveDto> saveMeasurements(String runId, MeasurementBatchDto request) {
        List<MeasurementSaveDto> next = request.measurements() == null ? List.of() : List.copyOf(request.measurements());
        measurementsByRunId.put(runId, next);
        appendAudit(new AuditEventRequestDto(
            emptyToDefault(request.reviewer(), "Reviewer"),
            "measurements_updated",
            emptyToDefault(request.detail(), "Mediciones revisadas para runId=" + runId)
        ));
        return next;
    }

    public AuditEventDto appendAudit(AuditEventRequestDto request) {
        AuditEventDto event = new AuditEventDto(
            "audit-" + UUID.randomUUID(),
            Instant.now(),
            emptyToDefault(request.reviewer(), "System"),
            emptyToDefault(request.action(), "event"),
            emptyToDefault(request.detail(), "")
        );
        auditTrail.add(event);
        if (auditTrail.size() > 200) {
            auditTrail.remove(0);
        }
        return event;
    }

    public List<AuditEventDto> auditTrail() {
        return auditTrail.stream().sorted(Comparator.comparing(AuditEventDto::timestamp).reversed()).limit(100).toList();
    }

    public ReviewStatusDto updateReview(String runId, ReviewUpdateRequestDto request) {
        String normalizedStatus = request.status().trim().toLowerCase();
        if (!ALLOWED_STATUSES.contains(normalizedStatus)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid review status");
        }

        ReviewStatusDto status = new ReviewStatusDto(
            runId,
            normalizedStatus,
            request.notes() == null ? "" : request.notes(),
            request.reviewer() == null ? "" : request.reviewer(),
            Instant.now()
        );
        store.put(runId, status);
        appendAudit(new AuditEventRequestDto(
            status.reviewer(),
            "review_" + normalizedStatus,
            "Revision " + normalizedStatus + " guardada para runId=" + runId
        ));
        return status;
    }

    private String emptyToDefault(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }
}
