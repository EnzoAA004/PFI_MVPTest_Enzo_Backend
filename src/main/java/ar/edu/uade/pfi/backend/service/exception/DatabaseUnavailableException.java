package ar.edu.uade.pfi.backend.service.exception;

public class DatabaseUnavailableException extends RuntimeException {
  public DatabaseUnavailableException(String message) {
    super(message);
  }

  public DatabaseUnavailableException(Throwable cause) {
    super("Base de datos no disponible", cause);
  }
}
