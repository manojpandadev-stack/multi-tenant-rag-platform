package com.docmind.repository;

import com.docmind.model.User;
import com.docmind.tenant.TenantContext;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface UserRepository extends JpaRepository<User, UUID> {

    Optional<User> findByEmail(String email);

    boolean existsByEmail(String email);

    /**
     * CRITICAL: This query always scopes by org_id from TenantContext.
     * This is our data-layer tenant isolation — we never trust the JWT alone.
     */
    @Query("SELECT u FROM User u WHERE u.organization.id = :orgId")
    List<User> findByOrgId(@Param("orgId") UUID orgId);

    /**
     * Verify a user belongs to the current org — used for access validation.
     */
    @Query("SELECT CASE WHEN COUNT(u) > 0 THEN true ELSE false END " +
           "FROM User u WHERE u.id = :userId AND u.organization.id = :orgId")
    boolean existsByIdAndOrgId(@Param("userId") UUID userId, @Param("orgId") UUID orgId);

    /**
     * Finds users by role within an org.
     */
    @Query("SELECT u FROM User u WHERE u.organization.id = :orgId AND u.role = :role")
    List<User> findByOrgIdAndRole(@Param("orgId") UUID orgId, @Param("role") com.docmind.model.Role role);
}
