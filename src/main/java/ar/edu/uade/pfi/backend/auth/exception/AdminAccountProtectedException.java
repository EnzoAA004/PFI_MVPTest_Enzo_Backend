package ar.edu.uade.pfi.backend.auth.exception;

import org.springframework.http.HttpStatus;

/**
 * Thrown when the professional activation/approval flow targets an account that already carries
 * ADMIN — that flow may only ever touch DOCTOR/REVIEWER accounts. Administrator accounts are
 * managed through a separate, dedicated flow (not implemented here).
 */
public class AdminAccountProtectedException extends RuntimeException {
  public AdminAccountProtectedException() {
    super("Las cuentas administrativas no pueden modificarse mediante el flujo de profesionales.");
  }

  public HttpStatus status() {
    return HttpStatus.CONFLICT;
  }

  public String code() {
    return "ADMIN_ACCOUNT_PROTECTED";
  }
}
