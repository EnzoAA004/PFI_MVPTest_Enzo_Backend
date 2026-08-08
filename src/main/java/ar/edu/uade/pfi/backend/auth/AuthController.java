package ar.edu.uade.pfi.backend.auth;

import ar.edu.uade.pfi.backend.auth.dto.AuthDtos.ApprovalRequest;
import ar.edu.uade.pfi.backend.auth.dto.AuthDtos.LoginRequest;
import ar.edu.uade.pfi.backend.auth.dto.AuthDtos.PendingAuthResponse;
import ar.edu.uade.pfi.backend.auth.dto.AuthDtos.ProfessionalActivationRequest;
import ar.edu.uade.pfi.backend.auth.dto.AuthDtos.ProfessionalActivationResponse;
import ar.edu.uade.pfi.backend.auth.dto.AuthDtos.RefreshRequest;
import ar.edu.uade.pfi.backend.auth.dto.AuthDtos.RegisterRequest;
import ar.edu.uade.pfi.backend.auth.dto.AuthDtos.SettingsRequest;
import ar.edu.uade.pfi.backend.auth.dto.AuthDtos.TokenResponse;
import ar.edu.uade.pfi.backend.auth.dto.AuthDtos.UserResponse;
import ar.edu.uade.pfi.backend.auth.dto.AuthDtos.VerifyRequest;
import ar.edu.uade.pfi.backend.service.AuditService;
import com.fasterxml.jackson.databind.JsonNode;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/auth")
public class AuthController {
  private final AuthService authService;
  private final AuditService auditService;
  private final RoleAuthorizationService authorizationService;

  public AuthController(
      AuthService authService,
      AuditService auditService,
      RoleAuthorizationService authorizationService) {
    this.authService = authService;
    this.auditService = auditService;
    this.authorizationService = authorizationService;
  }

  @PostMapping("/register")
  public PendingAuthResponse register(@Valid @RequestBody RegisterRequest request) {
    return authService.register(request);
  }

  @PostMapping("/verify-registration")
  public TokenResponse verifyRegistration(@Valid @RequestBody VerifyRequest request) {
    return authService.verify(request.challengeId(), request.code());
  }

  @PostMapping("/login")
  public Object login(@Valid @RequestBody LoginRequest request) {
    Object response = authService.login(request.email(), request.password());
    if (auditService != null) {
      auditService.record(
          "auth",
          "auth.login.completed",
          "login",
          "",
          Map.of("challengeRequired", response instanceof PendingAuthResponse));
    }
    return response;
  }

  @PostMapping("/verify-login")
  public TokenResponse verifyLogin(@Valid @RequestBody VerifyRequest request) {
    return authService.verify(request.challengeId(), request.code());
  }

  @PostMapping("/refresh")
  public TokenResponse refresh(@Valid @RequestBody RefreshRequest request) {
    return authService.refresh(request.refreshToken());
  }

  @PostMapping("/demo-doctor")
  public TokenResponse demoDoctor() {
    return authService.seedDemoDoctor();
  }

  @PostMapping("/logout")
  public ResponseEntity<Map<String, Object>> logout(
      @RequestBody(required = false) RefreshRequest request) {
    if (request != null) authService.logout(request.refreshToken());
    return ResponseEntity.ok(Map.of("status", "ok", "refreshTokenRevoked", request != null));
  }

  @GetMapping("/me")
  public UserResponse me(HttpServletRequest request) {
    TokenService.Claims claims =
        (TokenService.Claims) request.getAttribute(AuthFilter.AUTH_CLAIMS_ATTRIBUTE);
    if (claims == null) {
      return new UserResponse(
          "anonymous", "Reviewer", "", "", "", "", List.of("REVIEWER"), false, false, false, false);
    }
    return authService.currentUser(claims);
  }

  @PatchMapping("/settings")
  public UserResponse updateSettings(
      HttpServletRequest request, @RequestBody SettingsRequest settings) {
    TokenService.Claims claims =
        (TokenService.Claims) request.getAttribute(AuthFilter.AUTH_CLAIMS_ATTRIBUTE);
    return authService.updateSettings(claims, settings);
  }

  /**
   * P10-B.2: adds the same controller-level RoleAuthorizationService.requireAdmin gate every other
   * ADMIN-only endpoint uses, in addition to (not instead of) AuthService.listProfessionals' own
   * internal requireAdmin(claims) check — authorization must be blocked as early as possible, not
   * only proven safe by a service-layer check further down the call stack.
   */
  @GetMapping("/admin/professionals")
  public List<UserResponse> listProfessionals(HttpServletRequest request) {
    authorizationService.requireAdmin(request, "professional.list");
    TokenService.Claims claims =
        (TokenService.Claims) request.getAttribute(AuthFilter.AUTH_CLAIMS_ATTRIBUTE);
    return authService.listProfessionals(claims);
  }

  /**
   * Legacy endpoint kept only because the current production frontend still calls it — see
   * AuthService.approveProfessional. It now enforces exactly the same
   * RoleAuthorizationService.requireAdmin gate as /activation (previously it only relied on
   * AuthService's own internal role check). Deprecated: pending removal once the frontend migrates
   * to /activation (P10-C).
   */
  @Deprecated
  @PatchMapping("/admin/professionals/approval")
  public UserResponse updateProfessionalApproval(
      HttpServletRequest request, @Valid @RequestBody ApprovalRequest approval) {
    authorizationService.requireAdmin(request, "professional.approval");
    TokenService.Claims claims =
        (TokenService.Claims) request.getAttribute(AuthFilter.AUTH_CLAIMS_ATTRIBUTE);
    return authService.approveProfessional(claims, approval.email(), approval.approved());
  }

  private static final Set<String> ACTIVATION_ALLOWED_FIELDS = Set.of("email", "activated");
  private static final Pattern EMAIL_PATTERN = Pattern.compile("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$");

  /**
   * "Institutional manual activation": an ADMIN vouches for a professional account outside of any
   * email-verification flow (there is no real email provider today).
   * RoleAuthorizationService.requireAdmin is a mandatory constructor dependency — there is no
   * null-check bypass here, unlike the legacy pattern this project used to have on some
   * controllers.
   *
   * <p>Deliberately does NOT bind straight to {@code @Valid @RequestBody
   * ProfessionalActivationRequest}: under Spring Boot's actual auto-configured ObjectMapper (as
   * opposed to a hand-built `new ObjectMapper()` in a standalone MockMvc test), a record's
   * `@JsonIgnoreProperties(ignoreUnknown = false)` was empirically found to NOT reject extra fields
   * (verified in ProfessionalActivationRealObjectMapperTest before this fix, where it failed) — so
   * unknown fields ("roles", "admin", ...) are rejected explicitly here, against the raw JsonNode,
   * before the DTO is even constructed.
   */
  @PatchMapping("/admin/professionals/activation")
  public ProfessionalActivationResponse updateProfessionalActivation(
      HttpServletRequest request, @RequestBody JsonNode body) {
    authorizationService.requireAdmin(request, "professional.activation");
    ProfessionalActivationRequest activation = strictActivationRequest(body);
    TokenService.Claims claims =
        (TokenService.Claims) request.getAttribute(AuthFilter.AUTH_CLAIMS_ATTRIBUTE);
    return authService.activateProfessional(claims, activation.email(), activation.activated());
  }

  private ProfessionalActivationRequest strictActivationRequest(JsonNode body) {
    if (body == null || !body.isObject()) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Solicitud invalida");
    }
    Iterator<String> fieldNames = body.fieldNames();
    while (fieldNames.hasNext()) {
      if (!ACTIVATION_ALLOWED_FIELDS.contains(fieldNames.next())) {
        throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Solicitud invalida");
      }
    }
    JsonNode emailNode = body.get("email");
    String email = emailNode == null || emailNode.isNull() ? "" : emailNode.asText("").trim();
    if (email.isBlank() || !EMAIL_PATTERN.matcher(email).matches()) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Solicitud invalida");
    }
    JsonNode activatedNode = body.get("activated");
    if (activatedNode == null || !activatedNode.isBoolean()) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Solicitud invalida");
    }
    return new ProfessionalActivationRequest(email, activatedNode.asBoolean());
  }
}
