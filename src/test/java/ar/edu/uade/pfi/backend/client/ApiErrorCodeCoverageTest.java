package ar.edu.uade.pfi.backend.client;

import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import ar.edu.uade.pfi.backend.web.error.ApiErrorCode;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * P10-B.1 §8: every code the backend actually emits must resolve to a real {@link ApiErrorCode}
 * entry, never the {@code UNKNOWN} defensive fallback. This is an explicit, versioned list of
 * domain codes (not a filesystem/path-dependent search, per the task's own instruction) — when a
 * new domain code is introduced, add it here.
 */
class ApiErrorCodeCoverageTest {

  /** RunReviewService (RunReviewException) — see RunReviewService.java throw sites. */
  private static final List<String> RUN_REVIEW_CODES =
      List.of(
          "RUN_NOT_FOUND", "INVALID_REVIEW_STATUS", "REVIEWER_REQUIRED", "REVIEW_COMMENT_REQUIRED");

  /** StudyMetadataException throw sites (PatientHistoryService/StudyRunService). */
  private static final List<String> STUDY_METADATA_CODES =
      List.of("INVALID_SUBJECT_REFERENCE", "SUBJECT_REFERENCE_CONFLICT", "INVALID_REVIEW_PRIORITY");

  /** AuthFilter's own fixed codes (writeError call sites). */
  private static final List<String> AUTH_FILTER_CODES =
      List.of("AUTHENTICATION_REQUIRED", "ACCESS_DENIED", "AUTH_STATE_UNAVAILABLE");

  /**
   * Domain exceptions with a fixed code() (LastAdminProtectionException,
   * AdminAccountProtectedException, ...).
   */
  private static final List<String> DOMAIN_EXCEPTION_CODES =
      List.of(
          "ADMIN_ACCOUNT_PROTECTED",
          "LAST_ADMIN_PROTECTION",
          "STUDY_NOT_FOUND",
          "ASSET_CONTENT_UNAVAILABLE",
          "DATABASE_UNAVAILABLE",
          "AI_CONTRACT_VIOLATION",
          "AI_MULTIPLANAR_CONTRACT_VIOLATION",
          "INPUT_TOO_LARGE");

  /** ApiExceptionHandler's own generic ResponseStatusException → code mapping (codeForStatus). */
  private static final List<String> GENERIC_STATUS_CODES =
      List.of(
          "BAD_REQUEST",
          "AUTHENTICATION_REQUIRED",
          "ACCESS_DENIED",
          "NOT_FOUND",
          "CONFLICT",
          "UPSTREAM_UNAVAILABLE",
          "CLIENT_ERROR",
          "INTERNAL_ERROR",
          "VALIDATION_ERROR");

  /** AiServiceClient's own directly-constructed codes (translateException/dispatchV2Http). */
  private static final List<String> AI_SERVICE_CLIENT_CODES =
      List.of("AI_MODULE_ERROR", "AI_MODULE_TIMEOUT", "UPSTREAM_UNAVAILABLE");

  @Test
  void everyAiMultiplanarV2ErrorCodeMapperResultBelongsToTheCatalog() {
    for (AiMultiplanarV2ErrorCodeMapper.Mapped mapped : allKnownV2Mappings()) {
      assertNotNull(mapped.backendCode());
      assertNotEquals(
          ApiErrorCode.UNKNOWN,
          mapped.backendCode(),
          "AiMultiplanarV2ErrorCodeMapper produced a code that isn't a real catalog entry: "
              + mapped.backendCode());
    }
  }

  @Test
  void everyRunReviewServiceCodeBelongsToTheCatalog() {
    assertAllKnown(RUN_REVIEW_CODES);
  }

  @Test
  void everyStudyMetadataExceptionCodeBelongsToTheCatalog() {
    assertAllKnown(STUDY_METADATA_CODES);
  }

  @Test
  void everyAuthFilterCodeBelongsToTheCatalog() {
    assertAllKnown(AUTH_FILTER_CODES);
  }

  @Test
  void everyDomainExceptionCodeBelongsToTheCatalog() {
    assertAllKnown(DOMAIN_EXCEPTION_CODES);
  }

  @Test
  void everyGenericStatusMappedCodeBelongsToTheCatalog() {
    assertAllKnown(GENERIC_STATUS_CODES);
  }

  @Test
  void everyAiServiceClientDirectCodeBelongsToTheCatalog() {
    assertAllKnown(AI_SERVICE_CLIENT_CODES);
  }

  private void assertAllKnown(List<String> codes) {
    for (String code : codes) {
      assertNotEquals(
          ApiErrorCode.UNKNOWN,
          ApiErrorCode.fromCode(code),
          "Code not found in the ApiErrorCode catalog: " + code);
    }
  }

  private List<AiMultiplanarV2ErrorCodeMapper.Mapped> allKnownV2Mappings() {
    List<String> upstreamCodes =
        List.of(
            "INVALID_MULTIPLANAR_REQUEST",
            "NO_PLANE_REQUESTED",
            "INPUT_NOT_FOUND",
            "MODEL_NOT_FOUND",
            "MODEL_PLANE_MISMATCH",
            "MODEL_NOT_READY",
            "CONTRACT_FALLBACK_DISABLED",
            "REAL_INFERENCE_FAILED",
            "INVALID_MULTIPLANAR_RESPONSE",
            "UNSUPPORTED_INFERENCE_MODE",
            "SOME_TOTALLY_UNRECOGNIZED_UPSTREAM_CODE");
    return upstreamCodes.stream().map(AiMultiplanarV2ErrorCodeMapper::resolve).toList();
  }
}
