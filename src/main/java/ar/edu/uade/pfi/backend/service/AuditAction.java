package ar.edu.uade.pfi.backend.service;

/**
 * Stable catalog of audit actions for new/critical operations. Existing historical
 * literal action strings (e.g. {@code "asset.snapshot.stored"}, {@code "auth.login.completed"},
 * {@code "access.denied"}) are intentionally NOT migrated wholesale — P10-B does not
 * require breaking compatibility with anything already consuming those strings. New
 * critical operations, and any call site touched going forward, should use this catalog
 * via {@link #name()} instead of inventing another ad hoc literal.
 */
public enum AuditAction {
    AUTH_LOGIN_SUCCEEDED,
    AUTH_LOGIN_FAILED,
    AUTH_REFRESH_SUCCEEDED,
    AUTH_REFRESH_FAILED,
    PROFESSIONAL_ACTIVATED,
    PROFESSIONAL_DEACTIVATED,
    ADMIN_BOOTSTRAP_CREATED,
    AI_INPUT_UPLOADED,
    AI_RUN_REQUESTED,
    AI_RUN_COMPLETED,
    AI_RUN_FAILED,
    REVIEW_SAVED,
    REVIEW_UPDATED,
    ASSET_REQUESTED,
    ACCESS_DENIED,
    SECURITY_STATE_UNAVAILABLE
}
