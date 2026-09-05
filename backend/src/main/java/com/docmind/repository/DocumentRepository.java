package com.docmind.repository;

import com.docmind.model.Document;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.UUID;

public interface DocumentRepository extends JpaRepository<Document, UUID> {

    /**
     * Tenant-scoped: always filter by org_id at the data layer.
     */
    @Query("SELECT d FROM Document d WHERE d.organization.id = :orgId ORDER BY d.createdAt DESC")
    List<Document> findByOrgId(@Param("orgId") UUID orgId);

    /**
     * Paginated, tenant-scoped document listing.
     */
    @Query("SELECT d FROM Document d WHERE d.organization.id = :orgId ORDER BY d.createdAt DESC")
    Page<Document> findByOrgIdPaged(@Param("orgId") UUID orgId, Pageable pageable);

    /**
     * Paginated, tenant-scoped, status-filtered document listing.
     */
    @Query("SELECT d FROM Document d WHERE d.organization.id = :orgId AND d.status = :status ORDER BY d.createdAt DESC")
    Page<Document> findByOrgIdAndStatusPaged(
        @Param("orgId") UUID orgId,
        @Param("status") Document.ProcessingStatus status,
        Pageable pageable);

    @Query("SELECT d FROM Document d WHERE d.id = :docId AND d.organization.id = :orgId")
    Document findByIdAndOrgId(@Param("docId") UUID docId, @Param("orgId") UUID orgId);

    /**
     * Count documents for an org — used in usage metering.
     */
    @Query("SELECT COUNT(d) FROM Document d WHERE d.organization.id = :orgId")
    long countByOrgId(@Param("orgId") UUID orgId);

    /**
     * Sum file sizes for storage usage tracking.
     */
    @Query("SELECT COALESCE(SUM(d.fileSizeBytes), 0) FROM Document d WHERE d.organization.id = :orgId")
    long sumFileSizeByOrgId(@Param("orgId") UUID orgId);
}
