package com.docmind.service;

import com.docmind.dto.AuthDtos.*;
import com.docmind.exception.ResourceNotFoundException;
import com.docmind.model.Organization;
import com.docmind.model.Role;
import com.docmind.model.User;
import com.docmind.repository.OrganizationRepository;
import com.docmind.repository.UserRepository;
import com.docmind.security.JwtTokenProvider;
import com.docmind.tenant.TenantContext;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

/**
 * Authentication service handling signup (creates org + admin),
 * login (validates credentials + returns JWT), and invite.
 *
 * Key design: signup creates the org and first admin in a single
 * transaction. The org_id is embedded in the JWT token, enabling
 * tenant context on all subsequent requests.
 */
@Service
public class AuthService {

    private final UserRepository userRepository;
    private final OrganizationRepository orgRepository;
    private final JwtTokenProvider tokenProvider;
    private final PasswordEncoder passwordEncoder;

    public AuthService(
            UserRepository userRepository,
            OrganizationRepository orgRepository,
            JwtTokenProvider tokenProvider,
            PasswordEncoder passwordEncoder) {
        this.userRepository = userRepository;
        this.orgRepository = orgRepository;
        this.tokenProvider = tokenProvider;
        this.passwordEncoder = passwordEncoder;
    }

    /**
     * Sign up: creates org + first admin user in a single transaction.
     */
    @Transactional
    public AuthResponse signup(SignupRequest request) {
        if (userRepository.existsByEmail(request.email())) {
            throw new IllegalArgumentException("Email already registered");
        }

        // Create organization
        String slug = generateSlug(request.orgName());
        if (orgRepository.existsBySlug(slug)) {
            throw new IllegalArgumentException("Organization name is taken");
        }

        Organization org = Organization.builder()
            .name(request.orgName())
            .slug(slug)
            .build();
        org = orgRepository.save(org);

        // Create admin user (first user is always ORG_ADMIN)
        User user = User.builder()
            .email(request.email())
            .passwordHash(passwordEncoder.encode(request.password()))
            .fullName(request.fullName() != null ? request.fullName() : "")
            .organization(org)
            .role(Role.ORG_ADMIN)
            .isActive(true)
            .build();
        user = userRepository.save(user);

        return generateTokenPair(user, org);
    }

    /**
     * Login: validates credentials, returns JWT tokens.
     */
    @Transactional(readOnly = true)
    public AuthResponse login(LoginRequest request) {
        User user = userRepository.findByEmail(request.email())
            .orElseThrow(() -> new IllegalArgumentException("Invalid email or password"));

        if (!passwordEncoder.matches(request.password(), user.getPasswordHash())) {
            throw new IllegalArgumentException("Invalid email or password");
        }

        if (!Boolean.TRUE.equals(user.getIsActive())) {
            throw new IllegalArgumentException("Account is deactivated");
        }

        return generateTokenPair(user, user.getOrganization());
    }

    /**
     * Invite a new member to the current org.
     * Only ORG_ADMIN can do this.
     */
    @Transactional
    public UserDto invite(InviteRequest request, UUID callerId) {
        UUID orgId = TenantContext.getOrgId();
        if (orgId == null) {
            throw new SecurityException("No tenant context");
        }

        // Verify caller is org admin
        User caller = userRepository.findById(callerId)
            .orElseThrow(() -> new ResourceNotFoundException("User not found"));
        TenantContext.validateOrgAccess(caller.getOrgId());

        if (caller.getRole() != Role.ORG_ADMIN) {
            throw new SecurityException("Only org admins can invite members");
        }

        Organization org = orgRepository.findById(orgId)
            .orElseThrow(() -> new ResourceNotFoundException("Organization not found"));

        if (userRepository.existsByEmail(request.email())) {
            throw new IllegalArgumentException("Email already registered");
        }

        Role role = request.role() != null ? Role.valueOf(request.role()) : Role.MEMBER;

        User invitee = User.builder()
            .email(request.email())
            .passwordHash(passwordEncoder.encode("temporary-password-" + UUID.randomUUID()))
            .fullName(request.fullName() != null ? request.fullName() : "")
            .organization(org)
            .role(role)
            .isActive(true)
            .build();
        invitee = userRepository.save(invitee);

        return new UserDto(
            invitee.getId().toString(),
            invitee.getEmail(),
            invitee.getFullName(),
            org.getId().toString(),
            org.getName(),
            invitee.getRole().name()
        );
    }

    /**
     * Get current user profile.
     */
    @Transactional(readOnly = true)
    public UserDto getCurrentUser(UUID userId) {
        User user = userRepository.findById(userId)
            .orElseThrow(() -> new ResourceNotFoundException("User not found"));
        Organization org = user.getOrganization();

        return new UserDto(
            user.getId().toString(),
            user.getEmail(),
            user.getFullName(),
            org.getId().toString(),
            org.getName(),
            user.getRole().name()
        );
    }

    private AuthResponse generateTokenPair(User user, Organization org) {
        String accessToken = tokenProvider.generateAccessToken(
            user.getId(), org.getId(), user.getEmail(), user.getRole());
        String refreshToken = tokenProvider.generateRefreshToken(user.getId());

        UserDto userDto = new UserDto(
            user.getId().toString(),
            user.getEmail(),
            user.getFullName(),
            org.getId().toString(),
            org.getName(),
            user.getRole().name()
        );

        return new AuthResponse(accessToken, refreshToken, userDto);
    }

    private String generateSlug(String name) {
        return name.toLowerCase()
            .replaceAll("[^a-z0-9]+", "-")
            .replaceAll("^-|-$", "");
    }
}
