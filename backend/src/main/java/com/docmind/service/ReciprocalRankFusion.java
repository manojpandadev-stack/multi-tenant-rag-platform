package com.docmind.service;

import java.util.*;
import java.util.stream.Collectors;

/**
 * Reciprocal Rank Fusion (RRF) — combines ranked lists from multiple retrieval methods.
 *
 * Formula: score(d) = sum(1 / (k + rank_i(d))) across all ranked lists i
 * where k is the RRF constant (default 60, per the original RRF paper by Cormack et al.).
 *
 * Why RRF over weighted linear combination:
 * 1. No tuning needed — weights must be learned per-query distribution; RRF is parameter-free
 * 2. Rank-based — robust to different score scales (cosine vs tf-idf vs BM25)
 * 3. Well-established in IR literature (used by Elasticsearch, Bing, etc.)
 * 4. The k=60 constant works well across diverse query types
 *
 * This function is deliberately standalone (no DB, no LLM) for easy unit testing.
 */
public final class ReciprocalRankFusion {

    /** Standard RRF constant from the original paper. */
    public static final double DEFAULT_K = 60.0;

    private ReciprocalRankFusion() {}

    /**
     * A document with its fusion score and provenance (which lists it appeared in).
     */
    public record FusedResult<T>(
        T document,
        double rrfScore,
        Set<String> sources  // e.g. {"vector", "bm25"} or {"vector"} or {"bm25"}
    ) {}

    /**
     * Fuse two ranked lists using RRF.
     *
     * @param vectorResults  results from vector (cosine similarity) search, ranked best-first
     * @param bm25Results    results from BM25 keyword search, ranked best-first
     * @param k              RRF constant (default 60)
     * @param maxResults     maximum results to return
     * @return fused and re-ranked list of unique documents
     */
    public static <T> List<FusedResult<T>> fuse(
            List<T> vectorResults,
            List<T> bm25Results,
            double k,
            int maxResults) {

        Map<T, Double> rrfScores = new HashMap<>();
        Map<T, Set<String>> sources = new HashMap<>();

        // Score vector results
        for (int rank = 0; rank < vectorResults.size(); rank++) {
            T doc = vectorResults.get(rank);
            double rrfScore = 1.0 / (k + rank + 1);  // ranks are 1-indexed
            rrfScores.merge(doc, rrfScore, Double::sum);
            sources.computeIfAbsent(doc, d -> new LinkedHashSet<>()).add("vector");
        }

        // Score BM25 results
        for (int rank = 0; rank < bm25Results.size(); rank++) {
            T doc = bm25Results.get(rank);
            double rrfScore = 1.0 / (k + rank + 1);
            rrfScores.merge(doc, rrfScore, Double::sum);
            sources.computeIfAbsent(doc, d -> new LinkedHashSet<>()).add("bm25");
        }

        // Sort by RRF score descending, take top N
        return rrfScores.entrySet().stream()
            .map(e -> new FusedResult<>(e.getKey(), e.getValue(), sources.get(e.getKey())))
            .sorted(Comparator.comparingDouble((FusedResult<T> r) -> r.rrfScore()).reversed())
            .limit(maxResults)
            .collect(Collectors.toList());
    }

    /**
     * Convenience overload with defaults (k=60, max=10).
     */
    public static <T> List<FusedResult<T>> fuse(List<T> vectorResults, List<T> bm25Results) {
        return fuse(vectorResults, bm25Results, DEFAULT_K, 10);
    }
}
