package com.docmind.tenant;

import java.util.UUID;

/**
 * Thread-local holder for the current request's org_id.
 * Every request is scoped to exactly one organization.
 * This is the single source of truth for tenant context throughout the request lifecycle.
 */
public final class TenantContext {

    private static final ThreadLocal<UUID> CURRENT_ORG_ID = new InheritableThreadLocal<>();

    private TenantContext() {}

    public static void setOrgId(UUID orgId) {
        CURRENT_ORG_ID.set(orgId);
    }

    public static UUID getOrgId() {
        return CURRENT_ORG_ID.get();
    }

    public static void clear() {
        CURRENT_ORG_ID.remove();
    }

    /**
     * Validates that a given org_id matches the current tenant context.
     * Throws SecurityException if there's a mismatch.
     * This is our defense-in-depth check: even if someone bypasses the filter,
     * every service method can call this to verify isolation.
     */
    public static void validateOrgAccess(UUID resourceOrgId) {
        UUID currentOrgId = getOrgId();
        if (currentOrgId == null) {
            throw new SecurityException("No tenant context established for this request");
        }
        if (!currentOrgId.equals(resourceOrgId)) {
            throw new SecurityException(
                String.format("Cross-tenant access denied: requested org %s, current org %s",
                    resourceOrgId, currentOrgId)
            );
        }
    }
}
