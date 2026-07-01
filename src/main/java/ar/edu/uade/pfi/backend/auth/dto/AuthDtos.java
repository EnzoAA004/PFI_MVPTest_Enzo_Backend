package ar.edu.uade.pfi.backend.auth.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import java.util.List;

public final class AuthDtos {
    private AuthDtos() {}

    public record RegisterRequest(
        @NotBlank String fullName,
        @Email @NotBlank String email,
        @NotBlank String password,
        String licenseNumber,
        String specialty,
        String institution
    ) {}

    public record LoginRequest(
        @Email @NotBlank String email,
        @NotBlank String password
    ) {}

    public record VerifyRequest(
        @NotBlank String challengeId,
        @NotBlank String code
    ) {}

    public record RefreshRequest(
        @NotBlank String refreshToken
    ) {}

    public record PendingAuthResponse(
        String challengeId,
        String channel,
        int expiresInSeconds,
        String message,
        String devVerificationCode
    ) {}

    public record UserResponse(
        String id,
        String fullName,
        String email,
        String licenseNumber,
        String specialty,
        String institution,
        List<String> roles,
        boolean verified
    ) {}

    public record TokenResponse(
        String accessToken,
        String refreshToken,
        String tokenType,
        long expiresInSeconds,
        UserResponse user
    ) {}
}
