package com.docmind.controller;

import com.docmind.dto.AuthDtos.*;
import com.docmind.service.AuthService;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/auth")
public class AuthController {

    private final AuthService authService;

    public AuthController(AuthService authService) {
        this.authService = authService;
    }

    /**
     * Sign up: creates an organization and the first admin user.
     */
    @PostMapping("/signup")
    public ResponseEntity<AuthResponse> signup(@Valid @RequestBody SignupRequest request) {
        AuthResponse response = authService.signup(request);
        return ResponseEntity.ok(response);
    }

    /**
     * Login: returns JWT tokens.
     */
    @PostMapping("/login")
    public ResponseEntity<AuthResponse> login(@Valid @RequestBody LoginRequest request) {
        AuthResponse response = authService.login(request);
        return ResponseEntity.ok(response);
    }

    /**
     * Get current user profile.
     */
    @GetMapping("/me")
    public ResponseEntity<UserDto> me(Authentication authentication) {
        UUID userId = (UUID) authentication.getPrincipal();
        UserDto user = authService.getCurrentUser(userId);
        return ResponseEntity.ok(user);
    }

    /**
     * Invite a new member to the organization.
     * Only ORG_ADMIN can access this endpoint.
     */
    @PostMapping("/invite")
    public ResponseEntity<UserDto> invite(
            @Valid @RequestBody InviteRequest request,
            Authentication authentication) {
        UUID userId = (UUID) authentication.getPrincipal();
        UserDto invited = authService.invite(request, userId);
        return ResponseEntity.ok(invited);
    }

    @GetMapping("/health")
    public ResponseEntity<Map<String, String>> health() {
        return ResponseEntity.ok(Map.of("status", "UP", "service", "docmind-backend"));
    }
}
