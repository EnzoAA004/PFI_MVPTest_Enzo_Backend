package ar.edu.uade.pfi.backend.service;

import ar.edu.uade.pfi.backend.config.SafeLogSanitizer;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Recursive sanitizer specifically for AI Module diagnostics responses embedded in
 * {@code GET /api/system/diagnostics} (P10-B.1 §5). Unlike {@link AuditService#sanitize},
 * this is a blocklist, not a strict allowlist — the upstream health/readiness/models
 * responses have many benign fields (schemaVersion, model key/version, artifact hash,
 * quality status, ...) that must survive unchanged for v1/v2 verification to keep
 * working; only known-sensitive keys and values are removed/redacted.
 */
public final class DiagnosticsSanitizer {
    /**
     * Exact key-name matches (case-insensitive), not substring matches — "port"/"host"/
     * "file"/"path" as *substrings* would otherwise false-positive on innocuous keys
     * like "reportCount" or "profile" (caught during development, see
     * DiagnosticsSanitizerTest).
     */
    private static final Set<String> SENSITIVE_KEYS = Set.of(
        "url", "baseurl", "path", "file", "filename", "localpath", "storagepath",
        "token", "secret", "credential", "authorization", "password", "host", "port"
    );
    private static final int MAX_DEPTH = 6;
    private static final int MAX_LIST_ELEMENTS = 50;

    private DiagnosticsSanitizer() {
    }

    public static Map<String, Object> sanitize(Map<String, Object> raw) {
        return sanitize(raw, 0);
    }

    private static Map<String, Object> sanitize(Map<String, Object> raw, int depth) {
        if (raw == null || raw.isEmpty() || depth >= MAX_DEPTH) return Map.of();
        Map<String, Object> safe = new LinkedHashMap<>();
        for (Map.Entry<String, Object> entry : raw.entrySet()) {
            String key = entry.getKey() == null ? "" : entry.getKey();
            if (isSensitiveKey(key)) continue;
            safe.put(key, sanitizeValue(entry.getValue(), depth));
        }
        return safe;
    }

    private static Object sanitizeValue(Object value, int depth) {
        if (value == null) return null;
        if (value instanceof Number || value instanceof Boolean) return value;
        if (depth >= MAX_DEPTH) return SafeLogSanitizer.REDACTED;
        if (value instanceof Map<?, ?> nested) {
            Map<String, Object> typed = new LinkedHashMap<>();
            for (Map.Entry<?, ?> entry : nested.entrySet()) {
                typed.put(String.valueOf(entry.getKey()), entry.getValue());
            }
            return sanitize(typed, depth + 1);
        }
        if (value instanceof List<?> list) {
            List<Object> result = new ArrayList<>();
            int count = 0;
            for (Object item : list) {
                if (count++ >= MAX_LIST_ELEMENTS) break;
                result.add(sanitizeValue(item, depth + 1));
            }
            return result;
        }
        String text = String.valueOf(value);
        return SafeLogSanitizer.isSensitive(text) ? SafeLogSanitizer.REDACTED : text;
    }

    private static boolean isSensitiveKey(String key) {
        return SENSITIVE_KEYS.contains(key.toLowerCase(Locale.ROOT));
    }
}
