package ar.edu.uade.pfi.backend.service;

public class DatabaseUnavailableException extends RuntimeException {
    public DatabaseUnavailableException(Throwable cause) {
        super("Base de datos no disponible", cause);
    }
}
