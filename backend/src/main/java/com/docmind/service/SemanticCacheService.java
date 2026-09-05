package com.docmind.service;

import com.docmind.config.ObservabilityMetrics;
import com.docmind.model.Organization;
import com.docmind.model.SemanticCacheEntry;
import com.docmind.repository.SemanticCacheRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.util.*;

/**
 * Semantic caching service: avoids redundant LLM calls by detecting
 * semantically similar recent queries within the same org + document scope.
 *
 * Cache key = SHA-256(org_id + scope_filter)
 * Similarity: cosine similarity above a configurable threshold (default 0.95)
 *
 * We use Postgres for persistent storage and compute cosine similarity
 * in application code. For a small candidate set (recent entries per org+scope,
 * typically <500), this is fast enough (<1ms).
 *
 * At real scale (millions of cached queries), you would:
 * 1. Migrate to Redis Stack with RediSearch VECTOR similarity search
 * 2. Or use a dedicated vector DB (Pinecone, Weaviate) for the cache index
 * 3. Keep Postgres as the authoritative store for cache metadata
 *
 * Metrics delegated to ObservabilityMetrics (single source of truth for all
 * custom Micrometer metrics — avoids duplicate registration).
 */
@Service
public class SemanticCacheService {

    private static final Logger log = LoggerFactory.getLogger(SemanticCacheService.class);

    private final SemanticCacheRepository cacheRepository;
    private final EmbeddingService embeddingService;
    private final ObservabilityMetrics metrics;

    @Value("${cache.similarity-threshold:0.95}")
    private double similarityThreshold;

    @Value("${cache.ttl-hours:24}")
    private int ttlHours;

    @Value("${cache.max-entries-per-org:500}")
    private int maxEntriesPerOrg;

    // Cost tracking
    private long totalHits = 0;
    private long totalMisses = 0;
    private double totalCostSavedUsd = 0.0;
    private static final double COST_PER_1K_TOKENS = 0.00015; // GPT-4o-mini input
    private static final double COST_PER_QUERY_TOKENS = 800.0; // avg tokens per query

    public SemanticCacheService(
            SemanticCacheRepository cacheRepository,
            EmbeddingService embeddingService,
            ObservabilityMetrics metrics) {
        this.cacheRepository = cacheRepository;
        this.embeddingService = embeddingService;
        this.metrics = metrics;
    }

    /**
     * Result of a cache lookup.
     */
    public record CacheLookupResult(
        boolean hit,
        String cachedAnswer,
        List<String> sourceChunkIds,
        List<String> sourceDocIds,
        String modelUsed
    ) {}

    /**
     * Look up a cached answer for a query within the given org + document scope.
     *
     * Steps:
     * 1. Embed the query
     * 2. Find all non-expired cache entries for this org + scope
     * 3. Compute cosine similarity against each cached embedding
     * 4. If any entry exceeds the threshold, return it as a hit
     */
    @Transactional(readOnly = true)
    public CacheLookupResult lookup(String query, UUID orgId, List<UUID> docScopeFilter) {
        String scopeHash = computeScopeHash(orgId, docScopeFilter);
        Instant now = Instant.now();

        List<SemanticCacheEntry> candidates = cacheRepository
            .findByOrgIdAndScopeHashAndExpiresAtAfter(orgId, scopeHash, now);

        if (candidates.isEmpty()) {
            metrics.recordCacheMiss();
            totalMisses++;
            return new CacheLookupResult(false, null, null, null, null);
        }

        // Embed the query
        float[] queryEmbedding = embeddingService.embedBatch(List.of(query)).vectors().get(0);
        if (queryEmbedding == null) {
            metrics.recordCacheMiss();
            totalMisses++;
            return new CacheLookupResult(false, null, null, null, null);
        }

        // Find best match
        SemanticCacheEntry bestMatch = null;
        double bestSimilarity = -1.0;

        for (SemanticCacheEntry entry : candidates) {
            if (entry.getQueryEmbedding() == null) continue;
            double similarity = cosineSimilarity(queryEmbedding, entry.getQueryEmbedding());
            if (similarity > bestSimilarity) {
                bestSimilarity = similarity;
                bestMatch = entry;
            }
        }

        if (bestMatch != null && bestSimilarity >= similarityThreshold) {
            metrics.recordCacheHit();
            totalHits++;
            double costSaved = COST_PER_QUERY_TOKENS * COST_PER_1K_TOKENS / 1000.0;
            totalCostSavedUsd += costSaved;
            metrics.recordCost(costSaved * 100.0); // convert to cents

            log.debug("Cache HIT: similarity={}, query='{}', saved ${}",
                String.format("%.4f", bestSimilarity),
                query.length() > 50 ? query.substring(0, 50) + "..." : query,
                String.format("%.6f", costSaved));

            List<String> chunkIds = parseJsonArray(bestMatch.getSourceChunkIds());
            List<String> docIds = parseJsonArray(bestMatch.getSourceDocIds());

            return new CacheLookupResult(true, bestMatch.getAnswerText(), chunkIds, docIds, bestMatch.getModelUsed());
        }

        metrics.recordCacheMiss();
        totalMisses++;
        return new CacheLookupResult(false, null, null, null, null);
    }

    /**
     * Write a successful query result to the cache.
     */
    @Transactional
    public void write(String query, float[] queryEmbedding, String answer,
                      List<UUID> sourceChunkIds, List<UUID> sourceDocIds,
                      Organization org, List<UUID> docScopeFilter, String modelUsed, int tokenCount) {
        UUID orgId = org.getId();
        String scopeHash = computeScopeHash(orgId, docScopeFilter);

        // Evict old entries if over limit for this org+scope
        evictOldest(orgId, scopeHash);

        SemanticCacheEntry entry = SemanticCacheEntry.builder()
            .organization(org)
            .scopeHash(scopeHash)
            .queryText(query)
            .queryEmbedding(queryEmbedding)
            .answerText(answer)
            .sourceChunkIds(toJsonArray(sourceChunkIds))
            .sourceDocIds(toJsonArray(sourceDocIds))
            .modelUsed(modelUsed)
            .tokenCount(tokenCount)
            .expiresAt(Instant.now().plus(Duration.ofHours(ttlHours)))
            .build();

        cacheRepository.save(entry);

        log.debug("Cache WRITE: org={}, scope={}, ttl={}h, tokens={}",
            orgId, scopeHash.substring(0, 8), ttlHours, tokenCount);
    }

    /**
     * Invalidate all cache entries that reference a specific document.
     * Called on document deletion or reprocessing.
     */
    @Transactional
    public int invalidateByDocument(UUID documentId) {
        String docIdJson = "\"" + documentId + "\"";
        int deleted = cacheRepository.deleteByDocumentId(docIdJson);
        if (deleted > 0) {
            log.info("Cache INVALIDATED: {} entries for document {}", deleted, documentId);
        }
        return deleted;
    }

    /**
     * Clean up expired cache entries (runs every hour).
     */
    @Scheduled(fixedRate = 3600000) // every hour
    @Transactional
    public void cleanupExpired() {
        int deleted = cacheRepository.deleteExpiredAndCount(Instant.now());
        if (deleted > 0) {
            log.info("Cache cleanup: removed {} expired entries", deleted);
        }
    }

    /**
     * Get cache metrics for the given org.
     */
    public CacheMetrics getMetrics() {
        return new CacheMetrics(
            totalHits,
            totalMisses,
            totalHits + totalMisses > 0 ? (double) totalHits / (totalHits + totalMisses) : 0.0,
            totalCostSavedUsd
        );
    }

    public record CacheMetrics(long hits, long misses, double hitRate, double costSavedUsd) {}

    // ============================================================
    // Cosine similarity — unit-testable, no DB dependency
    // ============================================================

    /**
     * Compute cosine similarity between two vectors.
     * Returns a value in [-1, 1] where 1 means identical direction.
     */
    public static double cosineSimilarity(float[] a, float[] b) {
        if (a == null || b == null || a.length != b.length) {
            return 0.0;
        }
        double dot = 0.0;
        double normA = 0.0;
        double normB = 0.0;
        for (int i = 0; i < a.length; i++) {
            dot += a[i] * b[i];
            normA += a[i] * a[i];
            normB += b[i] * b[i];
        }
        if (normA == 0.0 || normB == 0.0) return 0.0;
        return dot / (Math.sqrt(normA) * Math.sqrt(normB));
    }

    /**
     * Check if two vectors are similar above a threshold.
     * Public for unit testing.
     */
    public static boolean isSimilar(float[] a, float[] b, double threshold) {
        return cosineSimilarity(a, b) >= threshold;
    }

    // ============================================================
    // Private helpers
    // ============================================================

    private String computeScopeHash(UUID orgId, List<UUID> docScopeFilter) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            StringBuilder input = new StringBuilder(orgId.toString());
            if (docScopeFilter != null && !docScopeFilter.isEmpty()) {
                List<String> sorted = docScopeFilter.stream()
                    .sorted()
                    .map(UUID::toString)
                    .toList();
                input.append(":").append(String.join(",", sorted));
            }
            byte[] hash = digest.digest(input.toString().getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder();
            for (byte b : hash) {
                hex.append(String.format("%02x", b));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException("SHA-256 not available", e);
        }
    }

    private void evictOldest(UUID orgId, String scopeHash) {
        List<SemanticCacheEntry> existing = cacheRepository
            .findByOrgIdAndScopeHashAndExpiresAtAfter(orgId, scopeHash, Instant.now());

        if (existing.size() >= maxEntriesPerOrg) {
            int toDelete = Math.max(1, existing.size() / 10);
            List<SemanticCacheEntry> toRemove = existing.stream()
                .sorted(Comparator.comparing(SemanticCacheEntry::getCreatedAt))
                .limit(toDelete)
                .toList();
            cacheRepository.deleteAll(toRemove);
            log.debug("Cache eviction: removed {} old entries for org={}", toRemove.size(), orgId);
        }
    }

    private String toJsonArray(List<UUID> uuids) {
        if (uuids == null || uuids.isEmpty()) return "[]";
        return "[" + uuids.stream().map(id -> "\"" + id + "\"").reduce((a, b) -> a + "," + b).orElse("") + "]";
    }

    private List<String> parseJsonArray(String json) {
        if (json == null || json.isBlank() || "[]".equals(json)) return List.of();
        return Arrays.stream(json.replace("[", "").replace("]", "").split(","))
            .map(s -> s.trim().replace("\"", ""))
            .filter(s -> !s.isEmpty())
            .toList();
    }
}
