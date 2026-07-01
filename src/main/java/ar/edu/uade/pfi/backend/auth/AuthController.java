package ar.edu.uade.pfi.backend.auth;

import ar.edu.uade.pfi.backend.auth.dto.AuthDtos.LoginRequest;
import ar.edu.uade.pfi.backend.auth.dto.AuthDtos.PendingAuthResponse;
import ar.edu.uade.pfi.backend.auth.dto.AuthDtos.RefreshRequest;
import ar.edu.uade.pfi.backend.auth.dto.AuthDtos.RegisterRequest;
import ar.edu.uade.pfi.backend.auth.dto.AuthDtos.TokenResponse;
import ar.edu.uade.pfi.backend.auth.dto.AuthDtos.UserResponse;
import ar.edu.uade.pfi.backend.auth.dto.AuthDtos.VerifyRequest;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import java.util.List;
import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/auth")
public class AuthController {
    private final AuthService authService;

    public AuthController(AuthService authService) {
        this.authService = authService;
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
    public PendingAuthResponse login(@Valid @RequestBody LoginRequest request) {
        return authService.login(request.email(), request.password());
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
    public ResponseEntity<Map<String, Object>> logout() {
        return ResponseEntity.ok(Map.of("status", "ok"));
    }

    @GetMapping("/me")
    public UserResponse me(HttpServletRequest request) {
        TokenService.Claims claims = (TokenService.Claims) request.getAttribute(AuthFilter.AUTH_CLAIMS_ATTRIBUTE);
        if (claims == null) {
            return new UserResponse("anonymous", "Reviewer", "", "", "", "", List.of("REVIEWER"), false);
        }
        return authService.currentUser(claims);
    }
}
