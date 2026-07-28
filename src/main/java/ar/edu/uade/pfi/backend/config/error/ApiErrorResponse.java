package ar.edu.uade.pfi.backend.config.error;

/**
 * The single, explicit error contract for this API. Field order here is preserved by
 * {@link ApiErrorWriter} when it serializes to the (still Map-based, for
 * per-exception-extensible fields like {@code runId}/{@code plane}/{@code assetName})
 * response body, so existing frontend field order/shape is unaffected.
 */
public record ApiErrorResponse(
    String status,
    String code,
    String message,
    String traceId,
    String path,
    String method,
    String timestamp,
    String category,
    boolean retryable,
    boolean humanReviewRequired,
    boolean notClinicalDiagnosis
) {
}
