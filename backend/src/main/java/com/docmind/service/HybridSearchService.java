package com.docmind.service;

import com.docmind.model.DocumentChunk;
import com.docmind.model.Organization;
import com.docmind.repository.DocumentChunkRepository;
import com.docmind.service.ReciprocalRankFusion.FusedResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.stream.Collectors;

/**
 * Hybrid search service combining BM25 keyword search + vector similarity search
 * via Reciprocal Rank Fusion, with optional Cohere re-ranking.
 *
 * Retrieval strategies (per-org via Organization.retrievalStrategy):
 * - VECTOR_ONLY: cosine similarity search only
 * - HYBRID: BM25 + vector → RRF fusion
 * - HYBRID_RERANK: BM25 + vector → RRF fusion → Cohere rerank
 */
@Service
public class HybridSearchService {

    private static final Logger log = LoggerFactory.getLogger(HybridSearchService.class);

    private final DocumentChunkRepository chunkRepository;
    private final EmbeddingService embeddingService;
    private final CohereRerankService rerankService;

    @Value("${retrieval.top-k:10}")
    private int defaultTopK;

    @Value("${retrieval.vector-search-size:20}")
    private int vectorSearchSize;

    @Value("${retrieval.bm25-search-size:20}")
    private int bm25SearchSize;

    public HybridSearchService(
            DocumentChunkRepository chunkRepository,
            EmbeddingService embeddingService,
            CohereRerankService rerankService) {
        this.chunkRepository = chunkRepository;
        this.embeddingService = embeddingService;
        this.rerankService = rerankService;
    }

    /**
     * A chunk with its retrieval score and provenance.
     */
    public record ScoredChunk(
        DocumentChunk chunk,
        double score,
        Set<String> sources,  // "vector", "bm25", or both
        String matchType      // "vector_only", "keyword_only", "both"
    ) {}

    /**
     * Run retrieval based on the org's configured strategy.
     */
    public List<ScoredChunk> search(String query, Organization org, int topK) {
        return switch (org.getRetrievalStrategy()) {
            case "vector-only", "VECTOR_ONLY" -> vectorOnlySearch(query, org.getId(), topK);
            case "hybrid", "HYBRID" -> hybridSearch(query, org.getId(), topK, false);
            case "hybrid+rerank", "HYBRID_RERANK" -> hybridSearch(query, org.getId(), topK, true);
            default -> vectorOnlySearch(query, org.getId(), topK);
        };
    }

    /**
     * Vector-only search (baseline for comparison).
     */
    private List<ScoredChunk> vectorOnlySearch(String query, UUID orgId, int topK) {
        float[] queryEmbedding = embeddingService.embedBatch(List.of(query)).vectors().get(0);
        if (queryEmbedding == null) {
            log.warn("Embedding failed for query, returning empty results");
            return List.of();
        }

        List<DocumentChunk> results = chunkRepository.findSimilarWithinOrg(
            orgId, queryEmbedding, topK);

        return results.stream()
            .map(chunk -> new ScoredChunk(chunk, 1.0, Set.of("vector"), "vector_only"))
            .toList();
    }

    /**
     * Hybrid search: BM25 + vector → RRF fusion → optional rerank.
     */
    private List<ScoredChunk> hybridSearch(String query, UUID orgId, int topK, boolean rerank) {
        // Run both searches independently
        float[] queryEmbedding = embeddingService.embedBatch(List.of(query)).vectors().get(0);

        List<DocumentChunk> vectorResults = queryEmbedding != null
            ? chunkRepository.findSimilarWithinOrg(orgId, queryEmbedding, vectorSearchSize)
            : List.of();

        List<DocumentChunk> bm25Results = chunkRepository.findByFullTextSearchWithinOrg(
            orgId, query, bm25SearchSize);

        log.debug("Hybrid search: {} vector results, {} BM25 results",
                vectorResults.size(), bm25Results.size());

        // Fuse with RRF
        List<FusedResult<DocumentChunk>> fused = ReciprocalRankFusion.fuse(
            vectorResults, bm25Results, ReciprocalRankFusion.DEFAULT_K, topK);

        // Map to ScoredChunks with match type
        List<ScoredChunk> scored = fused.stream()
            .map(f -> {
                String matchType = determineMatchType(f.sources());
                return new ScoredChunk(f.document(), f.rrfScore(), f.sources(), matchType);
            })
            .toList();

        // Optional re-ranking
        if (rerank && !scored.isEmpty()) {
            scored = rerankService.rerank(query, scored);
        }

        return scored;
    }

    private String determineMatchType(Set<String> sources) {
        if (sources.contains("vector") && sources.contains("bm25")) {
            return "both";
        } else if (sources.contains("vector")) {
            return "vector_only";
        } else {
            return "keyword_only";
        }
    }
}
