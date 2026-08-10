package ar.edu.uade.pfi.backend.service.exception;

import org.springframework.http.HttpStatus;

public class RunReviewException extends RuntimeException {
  private final HttpStatus status;
  private final String code;

  public RunReviewException(HttpStatus status, String code, String message) {
    super(message);
    this.status = status;
    this.code = code;
  }

  public HttpStatus status() {
    return status;
  }

  public String code() {
    return code;
  }
}
