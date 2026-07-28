package ar.edu.uade.pfi.backend.service;

import ar.edu.uade.pfi.backend.config.SafeLogSanitizer;
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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Service;

@Service
public class AuditService {
    private static final Logger log = LoggerFactory.getLogger(AuditService.class);
    private static final Set<String> SENSITIVE_KEY_PARTS = Set.of(
        "token", "secret", "password", "credential", "authorization", "path", "filename", "file", "email", "patient"
    );
    /** Defense-in-depth caps against accidental metadata growth — see P10-B §11. */
    private static final int MAX_METADATA_ENTRIES = 40;
    private static final int MAX_DEPTH = 5;
    private static final int MAX_LIST_ELEMENTS = 20;
    private static final int MAX_STRING_LENGTH = 160;

    private final StudyRepository repository;
    private final Clock clock;
    private final OperationalMetricsService metrics;

    @Autowired
    public AuditService(StudyRepository repository, @Nullable OperationalMetricsService metrics) {
        this(repository, Clock.systemUTC(), metrics);
    }

    public AuditService(StudyRepository repository) {
        this(repository, Clock.systemUTC(), null);
    }

    AuditService(StudyRepository repository, Clock clock) {
        this(repository, clock, null);
    }

    AuditService(StudyRepository repository, Clock clock, OperationalMetricsService metrics) {
        this.repository = repository;
        this.clock = clock;
        this.metrics = metrics;
    }

    /**
     * Never lets an audit-persistence failure mask the caller's real operation: on any
     * failure it logs a sanitized {@code event=audit_write_failed} line, increments the
     * {@code auditWriteFailures} counter, and returns {@code null} instead of throwing —
     * callers must treat audit as best-effort. This centralizes what used to be an
     * inconsistent try/catch pattern repeated (or, in some call sites, entirely missing)
     * across every caller.
     */
    public AuditEventResponseDto record(String actor, String action, String entityId, String traceId, Map<String, Object> metadata) {
        String safeActor = safeText(actor, "system");
        String safeAction = safeText(action, "unknown");
        String safeEntityId = safeText(entityId, "");
        String safeTraceId = safeText(traceId, "");
        try {
            DomainAuditEvent event = repository.saveAuditEvent(new DomainAuditEvent(
                UUID.randomUUID().toString(),
                safeActor,
                safeAction,
                safeEntityId,
                safeTraceId,
                clock.instant(),
                sanitize(metadata)
            ));
            return toResponse(event);
        } catch (RuntimeException ex) {
            if (metrics != null) metrics.incrementAuditWriteFailures();
            log.warn("event=audit_write_failed traceId={} action={} exceptionType={}", safeTraceId, safeAction, ex.getClass().getSimpleName());
            return null;
        }
    }

    public List<AuditEventResponseDto> findByTraceId(String traceId) {
        return repository.findAuditEventsByTraceId(traceId).stream().map(this::toResponse).toList();
    }

    public List<AuditEventResponseDto> findByEntityId(String entityId) {
        return repository.findAuditEventsByEntityId(entityId).stream().map(this::toResponse).toList();
    }

    public Map<String, Object> sanitize(Map<String, Object> metadata) {
        return sanitize(metadata, 0);
    }

    private Map<String, Object> sanitize(Map<String, Object> metadata, int depth) {
        if (metadata == null || metadata.isEmpty()) return Map.of();
        if (depth >= MAX_DEPTH) return Map.of();
        Map<String, Object> safe = new LinkedHashMap<>();
        int count = 0;
        for (Map.Entry<String, Object> entry : metadata.entrySet()) {
            if (count >= MAX_METADATA_ENTRIES) break;
            String key = entry.getKey() == null ? "" : entry.getKey();
            if (isSensitiveKey(key)) continue;
            safe.put(key, sanitizeValue(entry.getValue(), depth));
            count++;
        }
        return safe;
    }

    private Object sanitizeValue(Object value, int depth) {
        if (value == null) return "";
        if (value instanceof Number || value instanceof Boolean) return value;
        if (depth >= MAX_DEPTH) return "[redacted]";
        if (value instanceof Map<?, ?> nested) {
            Map<String, Object> typed = new LinkedHashMap<>();
            for (Map.Entry<?, ?> entry : nested.entrySet()) {
                typed.put(String.valueOf(entry.getKey()), entry.getValue());
            }
            return sanitize(typed, depth + 1);
        }
        if (value instanceof Iterable<?> values) {
            return toSafeList(values, depth);
        }
        String text = String.valueOf(value);
        // Value-based detection: a Bearer token/JWT/password/path/email can appear in a
        // metadata value even under an innocuous key (e.g. metadata["value"]) — key-name
        // filtering above is not sufficient on its own.
        if (looksLikePath(text) || SafeLogSanitizer.isSensitive(text)) return "[redacted]";
        return text.length() > MAX_STRING_LENGTH ? text.substring(0, MAX_STRING_LENGTH) : text;
    }

    private List<Object> toSafeList(Iterable<?> values, int depth) {
        java.util.ArrayList<Object> result = new java.util.ArrayList<>();
        int count = 0;
        for (Object value : values) {
            if (count >= MAX_LIST_ELEMENTS) break;
            result.add(sanitizeValue(value, depth + 1));
            count++;
        }
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
