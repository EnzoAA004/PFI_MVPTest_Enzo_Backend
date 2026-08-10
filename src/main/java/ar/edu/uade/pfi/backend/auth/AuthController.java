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
import ar.edu.uade.pfi.backend.web.error.ApiErrorResponse;
import com.fasterxml.jackson.databind.JsonNode;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
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
@Tag(
    name = "Autenticacion",
    description = "Alta, login en dos pasos y sesion. Es la puerta de entrada al resto de la API.")
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

  @Operation(
      summary = "Registra un profesional y abre un desafio de verificacion",
      description =
          """
          Da de alta la cuenta y devuelve un `challengeId`. **No devuelve token**: hay que
          completar la verificacion con `POST /api/auth/verify-registration`.

          La cuenta nace en `PENDING_APPROVAL`. Con ese estado se obtiene token, pero solo
          habilita `/api/auth/me` y `/api/auth/settings`; el resto de la API responde 403
          hasta que un ADMIN la aprueba.
          """)
  @ApiResponse(responseCode = "200", description = "Cuenta creada; devuelve `challengeId`.")
  @ApiResponse(
      responseCode = "400",
      description = "`VALIDATION_ERROR`: email invalido, password debil o campos faltantes.",
      content = @Content(schema = @Schema(implementation = ApiErrorResponse.class)))
  @ApiResponse(
      responseCode = "409",
      description = "`CONFLICT`: ya existe una cuenta con ese email.",
      content = @Content(schema = @Schema(implementation = ApiErrorResponse.class)))
  @PostMapping("/register")
  public PendingAuthResponse register(@Valid @RequestBody RegisterRequest request) {
    return authService.register(request);
  }

  @Operation(
      summary = "Completa la verificacion del registro y emite el token",
      description =
          "Cierra el desafio abierto por `POST /api/auth/register` y devuelve el access token"
              + " y el refresh token.")
  @ApiResponse(responseCode = "200", description = "Verificado; devuelve los tokens.")
  @ApiResponse(
      responseCode = "400",
      description = "Codigo invalido, vencido o `challengeId` desconocido.",
      content = @Content(schema = @Schema(implementation = ApiErrorResponse.class)))
  @PostMapping("/verify-registration")
  public TokenResponse verifyRegistration(@Valid @RequestBody VerifyRequest request) {
    return authService.verify(request.challengeId(), request.code());
  }

  @Operation(
      summary = "Inicia sesion",
      description =
          """
          Devuelve **una de dos formas** segun la configuracion de la cuenta, y el cliente
          tiene que distinguirlas:

          - `PendingAuthResponse` con `challengeId`, cuando hace falta segundo factor. Hay
            que seguir con `POST /api/auth/verify-login`.
          - `TokenResponse` con `accessToken` y `refreshToken`, cuando no hace falta.

          Un email inexistente y una password incorrecta devuelven lo mismo, a proposito: no
          se filtra si la cuenta existe.
          """)
  @ApiResponse(
      responseCode = "200",
      description = "Login aceptado: token, o desafio pendiente de verificacion.")
  @ApiResponse(
      responseCode = "401",
      description = "`AUTHENTICATION_REQUIRED`: credenciales invalidas.",
      content = @Content(schema = @Schema(implementation = ApiErrorResponse.class)))
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

  @Operation(
      summary = "Completa el segundo factor del login y emite el token",
      description =
          "Es el endpoint que produce el `accessToken` que usa el resto de la API en el header"
              + " `Authorization: Bearer <token>`.")
  @ApiResponse(responseCode = "200", description = "Verificado; devuelve los tokens.")
  @ApiResponse(
      responseCode = "400",
      description = "Codigo invalido, vencido o `challengeId` desconocido.",
      content = @Content(schema = @Schema(implementation = ApiErrorResponse.class)))
  @PostMapping("/verify-login")
  public TokenResponse verifyLogin(@Valid @RequestBody VerifyRequest request) {
    return authService.verify(request.challengeId(), request.code());
  }

  @Operation(
      summary = "Renueva el access token",
      description =
          """
          Devuelve un access token nuevo a partir de un refresh token vigente.

          El refresh token deja de servir si la cuenta cambia de estado -aprobacion,
          desactivacion, cambio de rol-: en ese caso este endpoint responde 401 y hay que
          volver a iniciar sesion. Es deliberado, para que una cuenta desactivada no siga
          operando con un token viejo.
          """)
  @ApiResponse(responseCode = "200", description = "Token renovado.")
  @ApiResponse(
      responseCode = "401",
      description = "`AUTHENTICATION_REQUIRED`: refresh token invalido, vencido o revocado.",
      content = @Content(schema = @Schema(implementation = ApiErrorResponse.class)))
  @PostMapping("/refresh")
  public TokenResponse refresh(@Valid @RequestBody RefreshRequest request) {
    return authService.refresh(request.refreshToken());
  }

  @Operation(
      summary = "Emite un token de demo (solo entornos locales)",
      description =
          """
          Devuelve un token con roles ADMIN/DOCTOR/REVIEWER sin registro ni verificacion, para
          poder recorrer el sistema en local o en una demo.

          **Solo existe si `PFI_AUTH_DEMO_ENABLED=true` y el perfil activo no es `prod` o
          `production`.** Con la bandera apagada la ruta deja de ser publica y responde 401
          como cualquier otra.

          No habilitarlo en un entorno accesible desde afuera: cualquiera que llegue al puerto
          obtiene un token de administrador.
          """)
  @ApiResponse(responseCode = "200", description = "Token de demo emitido.")
  @ApiResponse(
      responseCode = "401",
      description = "El modo demo esta deshabilitado.",
      content = @Content(schema = @Schema(implementation = ApiErrorResponse.class)))
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

  @Operation(
      summary = "Devuelve el usuario de la sesion actual",
      description =
          "Perfil, roles efectivos y estado de aprobacion del portador del token. Es uno de los"
              + " dos endpoints que una cuenta `PENDING_APPROVAL` puede llamar.")
  @ApiResponse(responseCode = "200", description = "Usuario actual.")
  @ApiResponse(
      responseCode = "401",
      description = "`AUTHENTICATION_REQUIRED`: sin token, token invalido o cuenta desactivada.",
      content = @Content(schema = @Schema(implementation = ApiErrorResponse.class)))
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
