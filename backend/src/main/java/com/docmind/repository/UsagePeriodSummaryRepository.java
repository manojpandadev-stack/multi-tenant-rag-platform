package com.docmind.repository;

import com.docmind.model.UsagePeriodSummary;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface UsagePeriodSummaryRepository extends JpaRepository<UsagePeriodSummary, UUID> {

    Optional<UsagePeriodSummary> findByOrganizationIdAndBillingPeriod(UUID orgId, String billingPeriod);

    List<UsagePeriodSummary> findByOrganizationIdAndBillingPeriodOrderByBillingPeriodDesc(
        UUID orgId, String beforePeriod, org.springframework.data.domain.Pageable pageable);

    /**
     * Native query to fetch a usage period, bypassing JPA persistence context caching.
     */
    @Query(value = """
        SELECT id, org_id, billing_period, documents_uploaded, storage_bytes,
            queries_total, queries_vector_only, queries_hybrid, queries_hybrid_rerank,
            embedding_tokens, llm_input_tokens, llm_output_tokens,
            cache_hits, cache_misses, rerank_calls, estimated_cost_cents,
            created_at, updated_at
        FROM usage_periods
        WHERE org_id = :orgId AND billing_period = :period
        """, nativeQuery = true)
    Optional<UsagePeriodSummary> findByOrgAndPeriodNative(
        @Param("orgId") UUID orgId, @Param("period") String billingPeriod);

    @Modifying
    @Transactional
    @Query(value = """
        UPDATE usage_periods SET
            documents_uploaded = documents_uploaded + :docs,
            storage_bytes = storage_bytes + :storageBytes,
            queries_total = queries_total + :queries,
            queries_vector_only = queries_vector_only + :vecOnly,
            queries_hybrid = queries_hybrid + :hybrid,
            queries_hybrid_rerank = queries_hybrid_rerank + :rerank,
            embedding_tokens = embedding_tokens + :embTokens,
            llm_input_tokens = llm_input_tokens + :llmInputTokens,
            llm_output_tokens = llm_output_tokens + :llmOutputTokens,
            cache_hits = cache_hits + :cacheHits,
            cache_misses = cache_misses + :cacheMisses,
            rerank_calls = rerank_calls + :rerankCalls,
            estimated_cost_cents = estimated_cost_cents + :costCents,
            updated_at = NOW()
        WHERE id = :id
        """, nativeQuery = true)
    int incrementCounters(
        @Param("id") UUID id,
        @Param("docs") int docs,
        @Param("storageBytes") long storageBytes,
        @Param("queries") int queries,
        @Param("vecOnly") int vecOnly,
        @Param("hybrid") int hybrid,
        @Param("rerank") int rerank,
        @Param("embTokens") long embTokens,
        @Param("llmInputTokens") long llmInputTokens,
        @Param("llmOutputTokens") long llmOutputTokens,
        @Param("cacheHits") int cacheHits,
        @Param("cacheMisses") int cacheMisses,
        @Param("rerankCalls") int rerankCalls,
        @Param("costCents") long costCents
    );
}
