package com.docmind.repository;

import com.docmind.model.DocumentChunk;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

public interface DocumentChunkRepository extends JpaRepository<DocumentChunk, UUID> {

    /**
     * Tenant-scoped chunk retrieval for a specific document.
     */
    @Query("SELECT c FROM DocumentChunk c WHERE c.document.id = :docId AND c.organization.id = :orgId ORDER BY c.chunkIndex")
    List<DocumentChunk> findByDocumentIdAndOrgId(@Param("docId") UUID docId, @Param("orgId") UUID orgId);

    /**
     * Count chunks for usage metering.
     */
    @Query("SELECT COUNT(c) FROM DocumentChunk c WHERE c.organization.id = :orgId")
    long countByOrgId(@Param("orgId") UUID orgId);

    /**
     * Find all chunks pending embedding for a document (for retry).
     */
    @Query("SELECT c FROM DocumentChunk c WHERE c.document.id = :docId AND c.organization.id = :orgId AND c.embeddingStatus = 'PENDING' ORDER BY c.chunkIndex")
    List<DocumentChunk> findPendingByDocumentIdAndOrgId(@Param("docId") UUID docId, @Param("orgId") UUID orgId);

    /**
     * pgvector ANN search scoped to an org.
     * Uses cosine distance (<=>) operator for similarity.
     */
    @Query(value = """
        SELECT c.* FROM document_chunks c
        WHERE c.org_id = :orgId
        ORDER BY c.embedding <=> CAST(:embedding AS vector)
        LIMIT :limit
        """, nativeQuery = true)
    List<DocumentChunk> findSimilarWithinOrg(
        @Param("orgId") UUID orgId,
        @Param("embedding") float[] embedding,
        @Param("limit") int limit);

    /**
     * Full-text search for BM25 (keyword search) scoped to an org.
     */
    @Query(value = """
        SELECT c.* FROM document_chunks c
        WHERE c.org_id = :orgId
        AND to_tsvector('english', c.content) @@ plainto_tsquery('english', :query)
        ORDER BY ts_rank(to_tsvector('english', c.content), plainto_tsquery('english', :query)) DESC
        LIMIT :limit
        """, nativeQuery = true)
    List<DocumentChunk> findByFullTextSearchWithinOrg(
        @Param("orgId") UUID orgId,
        @Param("query") String query,
        @Param("limit") int limit);
}
