package ar.edu.uade.pfi.backend.client.exception;

import org.springframework.http.HttpStatus;

/**
 * A structured error surfaced by the AI Module's pfi.multiplanar-run.v2 contract
 * (AiStructuredErrorV2Dto), translated to a stable backend error code. Never carries the upstream
 * stack trace or full response body.
 */
public class AiMultiplanarUpstreamException extends RuntimeException {
  private final HttpStatus status;
  private final String code;
  private final String aiTraceId;

  public AiMultiplanarUpstreamException(
      HttpStatus status, String code, String message, String aiTraceId) {
    super(message);
    this.status = status;
    this.code = code;
    this.aiTraceId = aiTraceId;
  }

  public HttpStatus status() {
    return status;
  }

  public String code() {
    return code;
  }

  public String aiTraceId() {
    return aiTraceId;
  }
}
