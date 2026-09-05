package com.docmind.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * Computes estimated costs based on configurable provider pricing.
 *
 * Current pricing (update when providers change prices):
 * - OpenAI text-embedding-3-small: $0.02/1M input tokens
 * - Groq llama-3.1-70b-versatile: $0.59/1M input tokens, $0.79/1M output tokens
 * - OpenAI gpt-4o-mini (fallback): $0.15/1M input tokens, $0.60/1M output tokens
 * - Cohere rerank-english-v3.0: $1.00/1000 queries
 *
 * Prices are stored in cents-per-million-tokens (or cents-per-1000-rerank-calls)
 * to avoid floating-point issues in billing calculations.
 */
@Component
public class CostCalculator {

    // Embedding pricing (cents per 1M tokens)
    private final double embeddingCostPerMillionTokens;

    // LLM pricing (cents per 1M tokens)
    private final double llmInputCostPerMillionTokens;
    private final double llmOutputCostPerMillionTokens;

    // Reranking pricing (cents per 1000 queries)
    private final double rerankCostPer1000Queries;

    public CostCalculator(
            @Value("${pricing.embedding.cost-per-million-tokens:2.0}") double embeddingCostPerMillionTokens,
            @Value("${pricing.llm.input-cost-per-million-tokens:15.0}") double llmInputCostPerMillionTokens,
            @Value("${pricing.llm.output-cost-per-million-tokens:60.0}") double llmOutputCostPerMillionTokens,
            @Value("${pricing.rerank.cost-per-1000-queries:100.0}") double rerankCostPer1000Queries) {
        this.embeddingCostPerMillionTokens = embeddingCostPerMillionTokens;
        this.llmInputCostPerMillionTokens = llmInputCostPerMillionTokens;
        this.llmOutputCostPerMillionTokens = llmOutputCostPerMillionTokens;
        this.rerankCostPer1000Queries = rerankCostPer1000Queries;
    }

    /**
     * Compute embedding cost in cents for a given token count.
     */
    public double embeddingCostCents(long tokens) {
        return tokens * embeddingCostPerMillionTokens / 1_000_000.0;
    }

    /**
     * Compute LLM cost in cents for given input/output token counts.
     */
    public double llmCostCents(long inputTokens, long outputTokens) {
        return llmInputCostCents(inputTokens) + llmOutputCostCents(outputTokens);
    }

    /**
     * LLM input token cost in cents (for breakdown display).
     */
    public double llmInputCostCents(long tokens) {
        return tokens * llmInputCostPerMillionTokens / 1_000_000.0;
    }

    /**
     * LLM output token cost in cents (for breakdown display).
     */
    public double llmOutputCostCents(long tokens) {
        return tokens * llmOutputCostPerMillionTokens / 1_000_000.0;
    }

    /**
     * Compute reranking cost in cents for a given number of rerank calls.
     */
    public double rerankCostCents(int calls) {
        return calls * rerankCostPer1000Queries / 1000.0;
    }

    /**
     * Compute total estimated cost in cents for a usage summary.
     */
    public double totalCostCents(long embeddingTokens, long llmInputTokens, long llmOutputTokens, int rerankCalls) {
        return embeddingCostCents(embeddingTokens)
            + llmCostCents(llmInputTokens, llmOutputTokens)
            + rerankCostCents(rerankCalls);
    }

    /**
     * Cost breakdown by category — maps directly to a billing dashboard chart.
     * Returns cents for each category.
     */
    public Map<String, Double> costBreakdown(long embeddingTokens, long llmInputTokens, long llmOutputTokens, int rerankCalls) {
        double emb = embeddingCostCents(embeddingTokens);
        double llmIn = llmInputCostCents(llmInputTokens);
        double llmOut = llmOutputCostCents(llmOutputTokens);
        double rerank = rerankCostCents(rerankCalls);
        return Map.of(
            "embedding_cents", emb,
            "llm_input_cents", llmIn,
            "llm_output_cents", llmOut,
            "rerank_cents", rerank,
            "total_cents", emb + llmIn + llmOut + rerank
        );
    }

    /**
     * Estimate average cost per query in cents.
     */
    public double costPerQuery(long totalCostCents, int queryCount) {
        if (queryCount == 0) return 0.0;
        return (double) totalCostCents / queryCount;
    }
}
