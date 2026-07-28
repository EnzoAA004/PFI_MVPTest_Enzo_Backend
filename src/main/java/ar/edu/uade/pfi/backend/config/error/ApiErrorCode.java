package ar.edu.uade.pfi.backend.config.error;

import java.util.Locale;

/**
 * Central, stable catalog of every `code` value the API can return. Existing codes are
 * preserved exactly as the frontend already consumes them — this is a catalog of what
 * already exists plus a place to classify it, not a renaming exercise.
 *
 * Each entry carries its {@link ApiErrorCategory} and whether it is transient-by-default
 * (see {@link ApiErrorWriter} for how that combines with HTTP status and the
 * inference-POST override to produce the final `retryable` flag).
 *
 * Historical aliases: none of the codes below were renamed from P10-A/P10-A.2 — they are
 * carried forward unchanged. {@code UNKNOWN} is a defensive fallback for any code string
 * not (yet) present in this catalog; it must never appear in a real response body under
 * normal operation, since every exception handled by {@code ApiExceptionHandler} passes a
 * code that is a member of this enum.
 */
public enum ApiErrorCode {
    AUTHENTICATION_REQUIRED(ApiErrorCategory.AUTHENTICATION, false),
    ACCESS_DENIED(ApiErrorCategory.AUTHORIZATION, false),
    AUTH_STATE_UNAVAILABLE(ApiErrorCategory.AUTHENTICATION, true),
    ADMIN_ACCOUNT_PROTECTED(ApiErrorCategory.SECURITY, false),
    LAST_ADMIN_PROTECTION(ApiErrorCategory.SECURITY, false),
    VALIDATION_ERROR(ApiErrorCategory.VALIDATION, false),
    BAD_REQUEST(ApiErrorCategory.VALIDATION, false),
    NOT_FOUND(ApiErrorCategory.RESOURCE, false),
    STUDY_NOT_FOUND(ApiErrorCategory.RESOURCE, false),
    ASSET_CONTENT_UNAVAILABLE(ApiErrorCategory.RESOURCE, false),
    /** Generic 409, e.g. duplicate registration — not a more specific domain code. */
    CONFLICT(ApiErrorCategory.RESOURCE, false),
    DATABASE_UNAVAILABLE(ApiErrorCategory.DATABASE, true),
    UPSTREAM_UNAVAILABLE(ApiErrorCategory.AI_UPSTREAM, true),
    AI_TIMEOUT(ApiErrorCategory.AI_UPSTREAM, true),
    AI_CONTRACT_VIOLATION(ApiErrorCategory.AI_CONTRACT, false),
    AI_MULTIPLANAR_CONTRACT_VIOLATION(ApiErrorCategory.AI_CONTRACT, false),
    INPUT_TOO_LARGE(ApiErrorCategory.VALIDATION, false),
    RUN_REVIEW_ERROR(ApiErrorCategory.RESOURCE, false),
    /** Generic fallback for a ResponseStatusException with an unmapped 4xx status. */
    CLIENT_ERROR(ApiErrorCategory.VALIDATION, false),
    INTERNAL_ERROR(ApiErrorCategory.INTERNAL, false),
    /** Defensive fallback only — should never be observed in a real response. */
    UNKNOWN(ApiErrorCategory.INTERNAL, false);

    private final ApiErrorCategory category;
    private final boolean transientByDefault;

    ApiErrorCode(ApiErrorCategory category, boolean transientByDefault) {
        this.category = category;
        this.transientByDefault = transientByDefault;
    }

    public ApiErrorCategory category() {
        return category;
    }

    /** Whether this code is transient by nature, before the HTTP-status and inference-POST overrides apply. */
    public boolean transientByDefault() {
        return transientByDefault;
    }

    public static ApiErrorCode fromCode(String code) {
        if (code == null || code.isBlank()) return UNKNOWN;
        try {
            return valueOf(code.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ex) {
            return UNKNOWN;
        }
    }
}
