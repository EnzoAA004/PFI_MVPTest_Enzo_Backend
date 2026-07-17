package ar.edu.uade.pfi.backend.domain;

import java.time.Instant;
import java.util.Map;
import java.util.Objects;

public record DomainAuditEvent(
    String id,
    String actor,
    String action,
    String entityId,
    String traceId,
    Instant timestamp,
    Map<String, Object> metadata
) {
    public DomainAuditEvent {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(actor, "actor");
        Objects.requireNonNull(action, "action");
        Objects.requireNonNull(entityId, "entityId");
        Objects.requireNonNull(traceId, "traceId");
        Objects.requireNonNull(timestamp, "timestamp");
        Objects.requireNonNull(metadata, "metadata");
        metadata = Map.copyOf(metadata);
    }
}
