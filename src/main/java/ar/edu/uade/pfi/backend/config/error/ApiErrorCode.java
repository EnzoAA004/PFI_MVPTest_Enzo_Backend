package ar.edu.uade.pfi.backend.config.error;

import java.util.Locale;

/**
 * Central, stable catalog of every `code` value the API can return. Existing codes are preserved
 * exactly as the frontend already consumes them — this is a catalog of what already exists plus a
 * place to classify it, not a renaming exercise.
 *
 * <p>Each entry carries its {@link ApiErrorCategory}, whether it is transient-by-default (see
 * {@link ApiErrorWriter} for how that combines with HTTP status and the inference-POST override to
 * produce the final `retryable` flag), and a fixed public message used instead of any
 * dynamic/upstream-derived message on a 5xx (P10-B.1 §1/§6).
 *
 * <p>P10-B.1 audit note: this catalog was found incomplete after P10-B — several codes genuinely
 * thrown by {@code RunReviewException}, {@code StudyMetadataException}, and {@code
 * AiMultiplanarV2ErrorCodeMapper} were missing, meaning {@link #fromCode} silently fell back to
 * {@link #UNKNOWN} for them (wrong category/retryable, though the code string itself still reached
 * the response body correctly since the body always uses the literal code, not this enum's name).
 * All real, currently-emitted codes are now present — see {@code ApiErrorCodeCoverageTest}. {@code
 * AI_TIMEOUT} and {@code RUN_REVIEW_ERROR} are kept as historical/reserved entries that are not
 * currently emitted by any code path (see the per-entry note) rather than removed, since removing
 * an enum constant is a needless compatibility risk for zero benefit.
 *
 * <p>{@code UNKNOWN} is a defensive fallback for any code string not (yet) present in this catalog;
 * it must never appear in a real response body under normal operation.
 */
public enum ApiErrorCode {
  AUTHENTICATION_REQUIRED(ApiErrorCategory.AUTHENTICATION, false, "Autenticacion requerida."),
  ACCESS_DENIED(
      ApiErrorCategory.AUTHORIZATION, false, "No tiene permisos para realizar esta operacion."),
  AUTH_STATE_UNAVAILABLE(
      ApiErrorCategory.AUTHENTICATION,
      true,
      "No fue posible validar el estado actual de la sesion."),
  ADMIN_ACCOUNT_PROTECTED(
      ApiErrorCategory.SECURITY,
      false,
      "Las cuentas administrativas no pueden modificarse mediante el flujo de profesionales."),
  LAST_ADMIN_PROTECTION(
      ApiErrorCategory.SECURITY,
      false,
      "La operacion dejaria al sistema sin un administrador activo."),
  VALIDATION_ERROR(ApiErrorCategory.VALIDATION, false, "Solicitud invalida."),
  BAD_REQUEST(ApiErrorCategory.VALIDATION, false, "Solicitud invalida."),
  NOT_FOUND(ApiErrorCategory.RESOURCE, false, "Recurso no encontrado."),
  STUDY_NOT_FOUND(ApiErrorCategory.RESOURCE, false, "Estudio no encontrado."),
  ASSET_CONTENT_UNAVAILABLE(ApiErrorCategory.RESOURCE, false, "Contenido del asset no disponible."),
  /** Generic 409, e.g. duplicate registration — not a more specific domain code. */
  CONFLICT(ApiErrorCategory.RESOURCE, false, "Conflicto de estado."),
  DATABASE_UNAVAILABLE(
      ApiErrorCategory.DATABASE, true, "Base de datos temporalmente no disponible."),
  UPSTREAM_UNAVAILABLE(
      ApiErrorCategory.AI_UPSTREAM, true, "El modulo de IA no esta disponible temporalmente."),
  /**
   * Reserved/historical: no current code path emits this literal code — AI_MODULE_TIMEOUT is what
   * v2 timeouts actually use. Kept for catalog stability.
   */
  AI_TIMEOUT(ApiErrorCategory.AI_UPSTREAM, true, "El modulo de IA no respondio a tiempo."),
  AI_CONTRACT_VIOLATION(
      ApiErrorCategory.AI_CONTRACT,
      false,
      "La respuesta del modulo de IA no cumple el contrato esperado."),
  AI_MULTIPLANAR_CONTRACT_VIOLATION(
      ApiErrorCategory.AI_CONTRACT,
      false,
      "La respuesta multiplanar no cumple el contrato esperado."),
  INPUT_TOO_LARGE(
      ApiErrorCategory.VALIDATION,
      false,
      "El archivo medico supera el tamano maximo permitido para carga."),
  /**
   * Reserved/historical: no current code path emits this literal code — RunReviewException always
   * carries a more specific code (RUN_NOT_FOUND, INVALID_REVIEW_STATUS, ...). Kept as a generic
   * fallback bucket.
   */
  RUN_REVIEW_ERROR(ApiErrorCategory.RESOURCE, false, "No fue posible procesar la revision."),
  /**
   * Generic fallback for a ResponseStatusException with an unmapped 4xx status — message is
   * computed dynamically by ApiExceptionHandler, not from this catalog.
   */
  CLIENT_ERROR(ApiErrorCategory.VALIDATION, false, "Solicitud invalida."),
  INTERNAL_ERROR(ApiErrorCategory.INTERNAL, false, "Error interno del backend."),

  // ---- RunReviewService (RunReviewException) ----
  RUN_NOT_FOUND(ApiErrorCategory.RESOURCE, false, "Run no encontrado."),
  INVALID_REVIEW_STATUS(ApiErrorCategory.VALIDATION, false, "reviewStatus invalido."),
  REVIEWER_REQUIRED(ApiErrorCategory.VALIDATION, false, "Reviewer es obligatorio."),
  REVIEW_COMMENT_REQUIRED(
      ApiErrorCategory.VALIDATION,
      false,
      "El estado requiere un comentario profesional descriptivo."),

  // ---- StudyMetadataService/StudyRunService/PatientHistoryService (StudyMetadataException) ----
  INVALID_SUBJECT_REFERENCE(ApiErrorCategory.VALIDATION, false, "subjectRef invalido."),
  SUBJECT_REFERENCE_CONFLICT(
      ApiErrorCategory.RESOURCE,
      false,
      "El estudio ya tiene una referencia de-identificada distinta."),
  INVALID_REVIEW_PRIORITY(ApiErrorCategory.VALIDATION, false, "reviewPriority invalido."),

  // ---- AI Module structured errors (AiMultiplanarV2ErrorCodeMapper →
  // AiMultiplanarUpstreamException) ----
  AI_INVALID_REQUEST(
      ApiErrorCategory.VALIDATION, false, "La solicitud enviada al modulo de IA es invalida."),
  AI_NO_PLANE_REQUESTED(
      ApiErrorCategory.VALIDATION, false, "No se especifico ningun plano para la inferencia."),
  AI_INPUT_NOT_FOUND(ApiErrorCategory.RESOURCE, false, "El input solicitado no esta disponible."),
  AI_MODEL_NOT_FOUND(ApiErrorCategory.RESOURCE, false, "El modelo solicitado no esta disponible."),
  /**
   * Documented classification choice: AI_CONTRACT rather than VALIDATION — a model/plane mismatch
   * reflects the AI Module's own model registry disagreeing with the contract it was asked to
   * fulfill, not a malformed caller request.
   */
  AI_MODEL_PLANE_MISMATCH(
      ApiErrorCategory.AI_CONTRACT,
      false,
      "El modelo solicitado no corresponde al plano solicitado."),
  AI_MODEL_NOT_READY(
      ApiErrorCategory.AI_UPSTREAM, false, "El modelo solicitado no esta listo para inferencia."),
  AI_CONTRACT_FALLBACK_DISABLED(
      ApiErrorCategory.AI_CONTRACT,
      false,
      "El modulo de IA no permite degradar el contrato solicitado."),
  /** Not auto-retried on POST — see ApiErrorWriter's non-idempotent-inference-POST override. */
  AI_REAL_INFERENCE_FAILED(
      ApiErrorCategory.AI_UPSTREAM, false, "La inferencia real del modulo de IA fallo."),
  AI_INVALID_RESPONSE(
      ApiErrorCategory.AI_CONTRACT, false, "La respuesta del modulo de IA es invalida."),
  AI_UNSUPPORTED_INFERENCE_MODE(
      ApiErrorCategory.VALIDATION, false, "El modo de inferencia solicitado no esta soportado."),
  // ---- Clasificador subarticular (AiSubarticularErrorCodeMapper) ----
  /**
   * El checkpoint congelado no esta configurado o el artefacto no esta en el entorno. Es
   * transitorio en el sentido operativo —se resuelve montando el .pt— y por eso el modulo de IA
   * responde 503 y no 500.
   */
  AI_SUBARTICULAR_UNAVAILABLE(
      ApiErrorCategory.AI_UPSTREAM,
      true,
      "El clasificador de hallazgos degenerativos no esta disponible."),
  AI_SUBARTICULAR_INVALID_INPUT(
      ApiErrorCategory.VALIDATION, false, "La coordenada solicitada al clasificador es invalida."),
  /**
   * El artefacto esta pero no es el que el codigo espera: hash distinto del declarado, o state_dict
   * incompatible. Es una falla de contrato entre el release del modelo y el del codigo, no un
   * problema de disponibilidad, y reintentarlo no cambia nada.
   */
  AI_SUBARTICULAR_CHECKPOINT_INVALID(
      ApiErrorCategory.AI_CONTRACT,
      false,
      "El modelo de hallazgos degenerativos no es el esperado."),
  AI_SUBARTICULAR_RUNTIME_ERROR(
      ApiErrorCategory.AI_UPSTREAM, false, "El clasificador de hallazgos degenerativos fallo."),

  /**
   * Transient by nature, but retryable is still forced false on a non-idempotent inference POST —
   * see ApiErrorWriter.
   */
  AI_MODULE_ERROR(ApiErrorCategory.AI_UPSTREAM, true, "El modulo de IA respondio con un error."),
  AI_MODULE_TIMEOUT(ApiErrorCategory.AI_UPSTREAM, true, "El modulo de IA no respondio a tiempo."),

  /** Defensive fallback only — should never be observed in a real response. */
  UNKNOWN(ApiErrorCategory.INTERNAL, false, "Error interno del backend.");

  private final ApiErrorCategory category;
  private final boolean transientByDefault;
  private final String publicMessage;

  ApiErrorCode(ApiErrorCategory category, boolean transientByDefault, String publicMessage) {
    this.category = category;
    this.transientByDefault = transientByDefault;
    this.publicMessage = publicMessage;
  }

  public ApiErrorCategory category() {
    return category;
  }

  /**
   * Whether this code is transient by nature, before the HTTP-status and inference-POST overrides
   * apply.
   */
  public boolean transientByDefault() {
    return transientByDefault;
  }

  /**
   * Fixed public message — never derived from an exception's own message, an upstream body, or a
   * stack trace.
   */
  public String publicMessage() {
    return publicMessage;
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
