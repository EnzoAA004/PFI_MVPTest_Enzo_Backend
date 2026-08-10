package ar.edu.uade.pfi.backend.service.exception;

public class StudyNotFoundException extends RuntimeException {
  public StudyNotFoundException(String caseId) {
    super("Study not found: " + caseId);
  }
}
