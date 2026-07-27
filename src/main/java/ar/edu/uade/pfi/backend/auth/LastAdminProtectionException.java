package ar.edu.uade.pfi.backend.auth;

import org.springframework.http.HttpStatus;

/** Thrown when an operation would leave the system without any non-demo ADMIN account. */
public class LastAdminProtectionException extends RuntimeException {
    public LastAdminProtectionException() {
        super("La operacion dejaria al sistema sin un administrador activo.");
    }

    public HttpStatus status() {
        return HttpStatus.CONFLICT;
    }

    public String code() {
        return "LAST_ADMIN_PROTECTION";
    }
}
