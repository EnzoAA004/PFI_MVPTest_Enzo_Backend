package ar.edu.uade.pfi.backend.repository;

public class DuplicatePatientReferenceException extends RuntimeException {
  public DuplicatePatientReferenceException(Throwable cause) {
    super("Patient reference already exists", cause);
  }
}
