package ar.edu.uade.pfi.backend.service;

import ar.edu.uade.pfi.backend.domain.DomainAuditEvent;
import ar.edu.uade.pfi.backend.dto.AuditEventResponseDto;
import ar.edu.uade.pfi.backend.repository.StudyRepository;
import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;

@Service
public class AuditService {
    private static final Set<String> SENSITIVE_KEY_PARTS = Set.of(
        "token", "secret", "password", "credential", "authorization", "path", "filename", "file", "email", "patient"
    );
    private final StudyRepository repository;
    private final Clock clock;

    public AuditService(StudyRepository repository) {
        this(repository, Clock.systemUTC());
    }

    AuditService(StudyRepository repository, Clock clock) {
        this.repository = repository;
        this.clock = clock;
    }

    public AuditEventResponseDto record(String actor, String action, String entityId, String traceId, Map<String, Object> metadata) {
        DomainAuditEvent event = repository.saveAuditEvent(new DomainAuditEvent(
            UUID.randomUUID().toString(),
            safeText(actor, "system"),
            safeText(action, "unknown"),
            safeText(entityId, ""),
            safeText(traceId, ""),
            clock.instant(),
            sanitize(metadata)
        ));
        return toResponse(event);
    }

    public List<AuditEventResponseDto> findByTraceId(String traceId) {
        return repository.findAuditEventsByTraceId(traceId).stream().map(this::toResponse).toList();
    }

    public List<AuditEventResponseDto> findByEntityId(String entityId) {
        return repository.findAuditEventsByEntityId(entityId).stream().map(this::toResponse).toList();
    }

    public Map<String, Object> sanitize(Map<String, Object> metadata) {
        if (metadata == null || metadata.isEmpty()) return Map.of();
        Map<String, Object> safe = new LinkedHashMap<>();
        for (Map.Entry<String, Object> entry : metadata.entrySet()) {
            String key = entry.getKey() == null ? "" : entry.getKey();
            if (isSensitiveKey(key)) continue;
            safe.put(key, sanitizeValue(entry.getValue()));
        }
        return safe;
    }

    private Object sanitizeValue(Object value) {
        if (value == null) return "";
        if (value instanceof Number || value instanceof Boolean) return value;
        if (value instanceof Map<?, ?> nested) {
            Map<String, Object> typed = new LinkedHashMap<>();
            for (Map.Entry<?, ?> entry : nested.entrySet()) {
                typed.put(String.valueOf(entry.getKey()), entry.getValue());
            }
            return sanitize(typed);
        }
        if (value instanceof Iterable<?> values) {
            return toSafeList(values);
        }
        String text = String.valueOf(value);
        if (looksLikePath(text)) return "[redacted]";
        return text.length() > 160 ? text.substring(0, 160) : text;
    }

    private List<Object> toSafeList(Iterable<?> values) {
        java.util.ArrayList<Object> result = new java.util.ArrayList<>();
        for (Object value : values) result.add(sanitizeValue(value));
        return result;
    }

    private boolean isSensitiveKey(String key) {
        String normalized = key.toLowerCase(Locale.ROOT);
        return SENSITIVE_KEY_PARTS.stream().anyMatch(normalized::contains);
    }

    private boolean looksLikePath(String value) {
        return value.contains("\\") || value.startsWith("/") || value.matches("^[A-Za-z]:\\\\.*") || value.contains("../") || value.contains("..\\");
    }

    private String safeText(String value, String fallback) {
        if (value == null || value.isBlank()) return fallback;
        return value.trim();
    }

    private AuditEventResponseDto toResponse(DomainAuditEvent event) {
        return new AuditEventResponseDto(
            event.id(),
            event.actor(),
            event.action(),
            event.entityId(),
            event.traceId(),
            event.timestamp(),
            event.metadata()
        );
    }
}
