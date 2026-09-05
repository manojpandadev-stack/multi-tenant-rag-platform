package com.docmind.service;

import com.docmind.config.ObservabilityMetrics;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import io.github.resilience4j.retry.annotation.Retry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.util.*;
import java.util.stream.Collectors;

/**
 * Cohere Rerank API integration.
 *
 * Uses Cohere's v2 rerank endpoint to re-order fused search results
 * with a cross-encoder model. Cost: ~$0.001 per query (1000 queries = $1).
 *
 * Fallback: if the API fails (rate limit, timeout, etc.), returns results
 * in RRF order without re-ranking. The query never fails entirely.
 *
 * Per-org toggle: controlled by Organization.retrievalStrategy:
 * - VECTOR_ONLY / HYBRID: no reranking
 * - HYBRID_RERANK: rerank the fused top-N
 */
@Service
public class CohereRerankService {

    private static final Logger log = LoggerFactory.getLogger(CohereRerankService.class);

    private final RestTemplate restTemplate;
    private final ObjectMapper objectMapper;
    private final ObservabilityMetrics metrics;

    @Value("${cohere.api-key:}")
    private String apiKey;

    @Value("${cohere.model:rerank-english-v3.0}")
    private String model;

    @Value("${cohere.rerank-top-n:5}")
    private int rerankTopN;

    public CohereRerankService(RestTemplate restTemplate, ObjectMapper objectMapper, ObservabilityMetrics metrics) {
        this.restTemplate = restTemplate;
        this.objectMapper = objectMapper;
        this.metrics = metrics;
    }

    /**
     * Re-rank scored chunks using Cohere's cross-encoder.
     * Falls back to original order if API fails.
     */
    @Retry(name = "cohere-rerank", fallbackMethod = "rerankFallback")
    @CircuitBreaker(name = "cohere-rerank", fallbackMethod = "rerankFallback")
    public List<HybridSearchService.ScoredChunk> rerank(
            String query,
            List<HybridSearchService.ScoredChunk> candidates) {

        if (apiKey == null || apiKey.isBlank()) {
            log.debug("No Cohere API key configured — skipping rerank");
            return candidates;
        }

        if (candidates.isEmpty()) {
            return candidates;
        }

        try {
            // Build Cohere rerank request
            List<String> documents = candidates.stream()
                .map(c -> c.chunk().getContent())
                .toList();

            Map<String, Object> request = new LinkedHashMap<>();
            request.put("model", model);
            request.put("query", query);
            request.put("documents", documents);
            request.put("top_n", Math.min(rerankTopN, candidates.size()));
            request.put("return_documents", false);

            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            headers.set("Authorization", "Bearer " + apiKey);

            HttpEntity<Map<String, Object>> entity = new HttpEntity<>(request, headers);

            ResponseEntity<String> response = restTemplate.exchange(
                "https://api.cohere.com/v2/rerank",
                HttpMethod.POST,
                entity,
                String.class);

            // Parse response
            JsonNode root = objectMapper.readTree(response.getBody());
            JsonNode results = root.get("results");

            if (results == null || !results.isArray()) {
                log.warn("Unexpected Cohere response format — returning original order");
                return candidates;
            }

            // Map Cohere indices back to our candidates
            List<HybridSearchService.ScoredChunk> reranked = new ArrayList<>();
            for (JsonNode result : results) {
                int originalIndex = result.get("index").asInt();
                double relevanceScore = result.get("relevance_score").asDouble();
                if (originalIndex < candidates.size()) {
                    HybridSearchService.ScoredChunk original = candidates.get(originalIndex);
                    reranked.add(new HybridSearchService.ScoredChunk(
                        original.chunk(),
                        relevanceScore,
                        original.sources(),
                        original.matchType()
                    ));
                }
            }

            metrics.recordRerankCall();
            log.debug("Cohere reranked {} candidates to {} results", candidates.size(), reranked.size());
            return reranked;

        } catch (Exception e) {
            log.warn("Cohere rerank failed: {} — returning original RRF order", e.getMessage());
            return candidates;
        }
    }

    /**
     * Fallback: return candidates unchanged (RRF order).
     */
    private List<HybridSearchService.ScoredChunk> rerankFallback(
            String query,
            List<HybridSearchService.ScoredChunk> candidates,
            Throwable t) {
        log.warn("Rerank fallback triggered: {}", t != null ? t.getMessage() : "unknown");
        return candidates;
    }
}
