package com.docmind.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public class AuthDtos {

    public record SignupRequest(
        @NotBlank(message = "Organization name is required")
        String orgName,

        @NotBlank(message = "Email is required")
        @Email(message = "Invalid email format")
        String email,

        @NotBlank(message = "Password is required")
        @Size(min = 8, message = "Password must be at least 8 characters")
        String password,

        String fullName
    ) {}

    public record LoginRequest(
        @NotBlank @Email String email,
        @NotBlank String password
    ) {}

    public record AuthResponse(
        String accessToken,
        String refreshToken,
        UserDto user
    ) {}

    public record InviteRequest(
        @NotBlank @Email String email,
        String fullName,
        String role
    ) {}

    public record UserDto(
        String id,
        String email,
        String fullName,
        String orgId,
        String orgName,
        String role
    ) {}
}
