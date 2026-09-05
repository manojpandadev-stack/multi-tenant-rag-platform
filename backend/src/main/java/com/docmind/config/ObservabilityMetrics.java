package com.docmind.config;

import io.micrometer.core.instrument.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Custom Micrometer metrics for DocMind operational visibility.
 *
 * These metrics go beyond what Actuator provides out-of-the-box:
 * - Cache hit rate (live gauge, not just per-period DB value)
 * - Retrieval strategy distribution
 * - Async processing queue depth
 * - Token consumption counters
 * - Rerank call counters
 *
 * Exposed via /actuator/prometheus for Prometheus scraping.
 */
@Component
public class ObservabilityMetrics {

    private static final Logger log = LoggerFactory.getLogger(ObservabilityMetrics.class);

    // Cache metrics
    private final Counter cacheHits;
    private final Counter cacheMisses;

    // Retrieval strategy counters
    private final Counter queriesVectorOnly;
    private final Counter queriesHybrid;
    private final Counter queriesHybridRerank;

    // Token counters
    private final Counter embeddingTokens;
    private final Counter llmTokens;
    private final Counter rerankCalls;

    // Async queue depth (gauges read from ThreadPoolTaskExecutor)
    private final AtomicLong asyncQueueSize = new AtomicLong(0);
    private final AtomicLong asyncActiveCount = new AtomicLong(0);

    // Cost tracking
    private final java.util.concurrent.atomic.DoubleAdder totalCostCents = new java.util.concurrent.atomic.DoubleAdder();

    public ObservabilityMetrics(MeterRegistry registry) {
        // Cache counters
        this.cacheHits = Counter.builder("docmind.cache.hits")
            .description("Total cache hits")
            .register(registry);
        this.cacheMisses = Counter.builder("docmind.cache.misses")
            .description("Total cache misses")
            .register(registry);

        // Retrieval strategy distribution
        this.queriesVectorOnly = Counter.builder("docmind.queries.vector_only")
            .description("Queries using vector-only retrieval")
            .register(registry);
        this.queriesHybrid = Counter.builder("docmind.queries.hybrid")
            .description("Queries using hybrid retrieval")
            .register(registry);
        this.queriesHybridRerank = Counter.builder("docmind.queries.hybrid_rerank")
            .description("Queries using hybrid + rerank retrieval")
            .register(registry);

        // Token counters
        this.embeddingTokens = Counter.builder("docmind.embedding.tokens")
            .description("Total embedding tokens consumed")
            .register(registry);
        this.llmTokens = Counter.builder("docmind.llm.tokens")
            .description("Total LLM tokens consumed (input + output)")
            .register(registry);
        this.rerankCalls = Counter.builder("docmind.rerank.calls")
            .description("Total Cohere rerank API calls")
            .register(registry);

        // Async queue gauges
        Gauge.builder("docmind.async.queue.size", asyncQueueSize, AtomicLong::doubleValue)
            .description("Async processing queue depth")
            .register(registry);
        Gauge.builder("docmind.async.active.count", asyncActiveCount, AtomicLong::doubleValue)
            .description("Async processing active thread count")
            .register(registry);

        // Cost gauge
        Gauge.builder("docmind.cost.total_cents", totalCostCents::sum)
            .description("Estimated total cost in cents for current period")
            .register(registry);

        log.info("ObservabilityMetrics initialized — custom metrics registered with Micrometer");
    }

    // ============================================================
    // Recording methods — called from service layer
    // ============================================================

    public void recordCacheHit() {
        cacheHits.increment();
    }

    public void recordCacheMiss() {
        cacheMisses.increment();
    }

    public void recordQueryStrategy(String strategy) {
        switch (strategy) {
            case "vector-only", "VECTOR_ONLY" -> queriesVectorOnly.increment();
            case "hybrid", "HYBRID" -> queriesHybrid.increment();
            case "hybrid+rerank", "HYBRID_RERANK" -> queriesHybridRerank.increment();
            case "cache-hit" -> {} // Don't count cache hits as retrieval strategy
            default -> log.debug("Unknown retrieval strategy: {}", strategy);
        }
    }

    public void recordEmbeddingTokens(int tokens) {
        embeddingTokens.increment(tokens);
    }

    public void recordLlmTokens(int inputTokens, int outputTokens) {
        llmTokens.increment(inputTokens + outputTokens);
    }

    public void recordRerankCall() {
        rerankCalls.increment();
    }

    public void recordCost(double costCents) {
        totalCostCents.add(costCents);
    }

    // Queue depth updates (called from AsyncConfig or pipeline)
    public void updateQueueDepth(int size, int activeCount) {
        asyncQueueSize.set(size);
        asyncActiveCount.set(activeCount);
    }

    // ============================================================
    // Snapshot for programmatic access (e.g., tests)
    // ============================================================

    public double getCacheHitRate() {
        double hits = cacheHits.count();
        double misses = cacheMisses.count();
        return (hits + misses) > 0 ? hits / (hits + misses) : 0.0;
    }

    public long getCacheHitCount() {
        return (long) cacheHits.count();
    }

    public long getCacheMissCount() {
        return (long) cacheMisses.count();
    }
}
