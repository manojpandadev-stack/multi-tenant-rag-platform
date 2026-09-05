package com.docmind.service;

import com.docmind.config.ObservabilityMetrics;
import com.docmind.exception.ResourceNotFoundException;
import com.docmind.model.Organization;
import com.docmind.repository.OrganizationRepository;
import com.docmind.tenant.TenantAwareService;
import io.micrometer.observation.annotation.Observed;
import io.micrometer.tracing.Span;
import io.micrometer.tracing.Tracer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;

/**
 * Query service with semantic caching.
 *
 * Flow:
 * 1. Check semantic cache for similar recent query (same org + doc scope)
 * 2. If cache hit → return cached answer immediately (skip LLM call)
 * 3. If cache miss → run retrieval → generate answer → write to cache → return
 *
 * This is the centerpiece cost-optimization: in production workloads,
 * 40-60% of queries are semantically similar to recent ones, and each
 * cached hit saves one LLM API call ($0.0001-$0.001 per query).
 */
@Service
public class QueryService {

    private static final Logger log = LoggerFactory.getLogger(QueryService.class);

    private final HybridSearchService hybridSearchService;
    private final OrganizationRepository orgRepository;
    private final TenantAwareService tenantAwareService;
    private final SemanticCacheService cacheService;
    private final EmbeddingService embeddingService;
    private final UsageRecordingService usageRecording;
    private final ObservabilityMetrics metrics;
    private final Tracer tracer;

    public QueryService(
            HybridSearchService hybridSearchService,
            OrganizationRepository orgRepository,
            TenantAwareService tenantAwareService,
            SemanticCacheService cacheService,
            EmbeddingService embeddingService,
            UsageRecordingService usageRecording,
            ObservabilityMetrics metrics,
            Tracer tracer) {
        this.hybridSearchService = hybridSearchService;
        this.orgRepository = orgRepository;
        this.tenantAwareService = tenantAwareService;
        this.cacheService = cacheService;
        this.embeddingService = embeddingService;
        this.usageRecording = usageRecording;
        this.metrics = metrics;
        this.tracer = tracer;
    }

    public record QueryResult(
        List<HybridSearchService.ScoredChunk> chunks,
        String retrievalStrategy,
        int totalChunksFound,
        boolean cacheHit,
        String cachedAnswer,
        List<String> cachedSourceChunkIds
    ) {}

    /**
     * Run retrieval for a query, with semantic caching.
     */
    @Observed(name = "query")
    @Transactional(readOnly = true)
    public QueryResult query(String question, UUID userId, Integer topK, List<UUID> docScopeFilter) {
        UUID orgId = tenantAwareService.requireCurrentOrgId(userId);
        Organization org = orgRepository.findById(orgId)
            .orElseThrow(() -> new ResourceNotFoundException("Organization not found"));

        // Set trace context for structured logging
        MDC.put("org_id", orgId.toString());
        Span currentSpan = tracer.currentSpan();
        if (currentSpan != null) {
            currentSpan.tag("org_id", orgId.toString());
            currentSpan.tag("query.text", question.length() > 100 ? question.substring(0, 100) + "..." : question);
        }

        int k = topK != null ? topK : 10;

        try {
            // Step 1: Check semantic cache
            SemanticCacheService.CacheLookupResult cacheResult = cacheService.lookup(
                question, orgId, docScopeFilter);

            if (cacheResult.hit()) {
                log.info("Query served from cache: '{}' (org={})", question, orgId);
                metrics.recordCacheHit();
                metrics.recordQueryStrategy("cache-hit");
                usageRecording.recordCacheHit(orgId, "cache-hit");
                if (currentSpan != null) currentSpan.tag("cache.hit", "true");
                return new QueryResult(
                    List.of(),
                    "cache-hit",
                    0,
                    true,
                    cacheResult.cachedAnswer(),
                    cacheResult.sourceChunkIds()
                );
            }

            // Step 2: Cache miss — run retrieval
            if (currentSpan != null) currentSpan.tag("cache.hit", "false");
            metrics.recordCacheMiss();
            log.debug("Cache miss for query '{}' — running retrieval", question);
            List<HybridSearchService.ScoredChunk> results = hybridSearchService.search(question, org, k);

            // Record usage and metrics
            int embTokens = results.stream().mapToInt(sc -> sc.chunk().getTokenCount() != null ? sc.chunk().getTokenCount() : 0).sum();
            metrics.recordQueryStrategy(org.getRetrievalStrategy());
            if (currentSpan != null) {
                currentSpan.tag("retrieval.strategy", org.getRetrievalStrategy());
                currentSpan.tag("retrieval.chunks_found", String.valueOf(results.size()));
            }
            usageRecording.recordQuery(orgId, org.getRetrievalStrategy(), embTokens, 0, 0, false, 0.0);

            return new QueryResult(
                results,
                org.getRetrievalStrategy(),
                results.size(),
                false,
                null,
                null
            );
        } finally {
            MDC.remove("org_id");
        }
    }

    /**
     * Convenience overload without doc scope filter.
     */
    @Transactional(readOnly = true)
    public QueryResult query(String question, UUID userId, Integer topK) {
        return query(question, userId, topK, null);
    }

    /**
     * Write a successful query result to the cache.
     * Called after the LLM response is generated.
     */
    public void cacheResult(String question, String answer, List<HybridSearchService.ScoredChunk> chunks,
                            UUID orgId, List<UUID> docScopeFilter, String modelUsed) {
        try {
            // Embed the question for future similarity lookups
            float[] queryEmbedding = embeddingService.embedBatch(List.of(question)).vectors().get(0);
            if (queryEmbedding == null) {
                log.warn("Failed to embed question for cache write — skipping");
                return;
            }

            List<UUID> chunkIds = chunks.stream()
                .map(sc -> sc.chunk().getId())
                .toList();
            List<UUID> docIds = chunks.stream()
                .map(sc -> sc.chunk().getDocument().getId())
                .distinct()
                .toList();

            Organization org = orgRepository.findById(orgId).orElse(null);
            if (org == null) return;

            int tokenCount = chunks.stream()
                .mapToInt(sc -> sc.chunk().getTokenCount() != null ? sc.chunk().getTokenCount() : 0)
                .sum();

            cacheService.write(question, queryEmbedding, answer, chunkIds, docIds,
                org, docScopeFilter, modelUsed, tokenCount);
        } catch (Exception e) {
            // Cache write failure should never break the query flow
            log.warn("Failed to write to semantic cache: {}", e.getMessage());
        }
    }
}
