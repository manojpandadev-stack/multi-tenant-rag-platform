package com.docmind.repository;

import com.docmind.model.SemanticCacheEntry;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public interface SemanticCacheRepository extends JpaRepository<SemanticCacheEntry, UUID> {

    /**
     * Find cache entries for a specific org + scope that haven't expired.
     */
    @Query("SELECT e FROM SemanticCacheEntry e WHERE e.organization.id = :orgId AND e.scopeHash = :scopeHash AND e.expiresAt > :now")
    List<SemanticCacheEntry> findByOrgIdAndScopeHashAndExpiresAtAfter(
        @Param("orgId") UUID orgId, @Param("scopeHash") String scopeHash, @Param("now") Instant now);

    /**
     * Find all cache entries that reference a specific document (for invalidation).
     * Uses JSONB containment operator.
     */
    @Modifying
    @Transactional
    @Query(value = """
        DELETE FROM semantic_cache
        WHERE source_doc_ids @> CAST(:docId AS jsonb)
        """, nativeQuery = true)
    int deleteByDocumentId(@Param("docId") String docIdJson);

    /**
     * Delete all expired cache entries and return count.
     */
    @Modifying
    @Transactional
    @Query("DELETE FROM SemanticCacheEntry e WHERE e.expiresAt < :now")
    int deleteExpiredAndCount(@Param("now") Instant now);

    /**
     * Count cache entries for an org.
     */
    @Query("SELECT COUNT(e) FROM SemanticCacheEntry e WHERE e.organization.id = :orgId")
    long countByOrgId(@Param("orgId") UUID orgId);

    /**
     * Delete all cache entries for an org.
     */
    @Modifying
    @Transactional
    @Query("DELETE FROM SemanticCacheEntry e WHERE e.organization.id = :orgId")
    void deleteByOrgId(@Param("orgId") UUID orgId);
}
