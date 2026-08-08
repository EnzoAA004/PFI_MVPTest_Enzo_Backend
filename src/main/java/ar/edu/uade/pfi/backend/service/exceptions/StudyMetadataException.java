package ar.edu.uade.pfi.backend.service.exceptions;

import org.springframework.http.HttpStatus;

public class StudyMetadataException extends RuntimeException {
  private final HttpStatus status;
  private final String code;

  public StudyMetadataException(HttpStatus status, String code, String message) {
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
