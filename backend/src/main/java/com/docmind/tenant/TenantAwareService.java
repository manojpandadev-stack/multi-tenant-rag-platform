package com.docmind.tenant;

import com.docmind.model.User;
import com.docmind.repository.UserRepository;
import org.springframework.stereotype.Component;

import java.util.UUID;

/**
 * Provides common tenant isolation checks used by all services.
 *
 * Design decision: Defense-in-depth isolation.
 * 1. TenantFilter sets org_id from JWT (request entry point)
 * 2. Every repository query filters by org_id (data layer)
 * 3. This service validates resource ownership (service layer)
 *
 * Even if someone forges a JWT with a different org_id, step 2 ensures
 * they can never read another org's data at the database level.
 * This is the "never trust the JWT alone" principle in action.
 */
@Component
public class TenantAwareService {

    private final UserRepository userRepository;

    public TenantAwareService(UserRepository userRepository) {
        this.userRepository = userRepository;
    }

    /**
     * Gets and validates the current tenant org_id.
     * Throws if no context or if the user doesn't belong to the claimed org.
     */
    public UUID requireCurrentOrgId(UUID userId) {
        UUID orgId = TenantContext.getOrgId();
        if (orgId == null) {
            throw new SecurityException("No tenant context established");
        }

        // Verify the user actually belongs to this org in the database
        if (!userRepository.existsByIdAndOrgId(userId, orgId)) {
            throw new SecurityException(
                "User " + userId + " does not belong to org " + orgId);
        }

        return orgId;
    }

    /**
     * Validates a resource's org_id matches the current tenant.
     * Use this when you have a loaded entity and want to verify access.
     */
    public void validateResourceAccess(UUID resourceOrgId) {
        TenantContext.validateOrgAccess(resourceOrgId);
    }
}
