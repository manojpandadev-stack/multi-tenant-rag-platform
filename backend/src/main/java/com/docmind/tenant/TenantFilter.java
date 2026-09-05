package com.docmind.tenant;

import com.docmind.security.JwtTokenProvider;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.UUID;

/**
 * Establishes tenant context from the JWT token on every request.
 * This filter runs BEFORE any business logic and sets the org_id
 * into the thread-local TenantContext.
 *
 * Design decision: We extract org_id from the JWT claims (which was set
 * during token creation) AND we'll validate it against the DB in service
 * methods. JWT alone is not trusted — this is defense-in-depth.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class TenantFilter extends OncePerRequestFilter {

    private final JwtTokenProvider tokenProvider;

    public TenantFilter(JwtTokenProvider tokenProvider) {
        this.tokenProvider = tokenProvider;
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain) throws ServletException, IOException {

        try {
            String token = extractToken(request);
            if (token != null && tokenProvider.validateToken(token)) {
                UUID orgId = tokenProvider.getOrgIdFromToken(token);
                if (orgId != null) {
                    TenantContext.setOrgId(orgId);
                }
            }

            filterChain.doFilter(request, response);
        } finally {
            // Always clear to prevent thread-local leaks in the pool
            TenantContext.clear();
        }
    }

    private String extractToken(HttpServletRequest request) {
        String header = request.getHeader("Authorization");
        if (header != null && header.startsWith("Bearer ")) {
            return header.substring(7);
        }
        return null;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getRequestURI();
        // Skip tenant context for public endpoints
        return path.startsWith("/api/auth/")
            || path.startsWith("/actuator/")
            || path.startsWith("/swagger-ui")
            || path.startsWith("/api-docs");
    }
}
