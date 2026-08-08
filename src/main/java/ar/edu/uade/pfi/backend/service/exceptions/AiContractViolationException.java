package ar.edu.uade.pfi.backend.service.exceptions;

public class AiContractViolationException extends RuntimeException {
  public AiContractViolationException(String message) {
    super(message);
  }
}
