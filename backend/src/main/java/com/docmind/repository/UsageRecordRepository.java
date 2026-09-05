package com.docmind.repository;

import com.docmind.model.UsageRecord;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.UUID;

public interface UsageRecordRepository extends JpaRepository<UsageRecord, UUID> {

    @Query("SELECT COALESCE(SUM(u.tokensConsumed), 0) FROM UsageRecord u " +
           "WHERE u.organization.id = :orgId AND u.createdAt >= :since")
    long sumTokensByOrgSince(@Param("orgId") UUID orgId, @Param("since") Instant since);

    @Query("SELECT COUNT(u) FROM UsageRecord u " +
           "WHERE u.organization.id = :orgId AND u.createdAt >= :since")
    long countQueriesByOrgSince(@Param("orgId") UUID orgId, @Param("since") Instant since);

    @Query("SELECT COUNT(u) FROM UsageRecord u " +
           "WHERE u.organization.id = :orgId AND u.isCacheHit = true AND u.createdAt >= :since")
    long countCacheHitsByOrgSince(@Param("orgId") UUID orgId, @Param("since") Instant since);

    @Query("SELECT COALESCE(SUM(u.estimatedCostCents), 0) FROM UsageRecord u " +
           "WHERE u.organization.id = :orgId AND u.createdAt >= :since")
    long sumCostByOrgSince(@Param("orgId") UUID orgId, @Param("since") Instant since);
}
