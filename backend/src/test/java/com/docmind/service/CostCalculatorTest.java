package com.docmind.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for CostCalculator — verifies cost math is exact for known inputs.
 * No DB, no API calls — pure math validation.
 */
class CostCalculatorTest {

    // Use production default pricing for tests
    private final CostCalculator calculator = new CostCalculator(
        2.0,   // embedding: $0.02/1M tokens (2.0 cents)
        15.0,  // LLM input: $0.15/1M tokens (15.0 cents)
        60.0,  // LLM output: $0.60/1M tokens (60.0 cents)
        100.0  // rerank: $1.00/1K queries (100.0 cents)
    );

    @Test
    @DisplayName("Embedding cost: 1000 tokens = $0.00002")
    void embeddingCostBasic() {
        double cost = calculator.embeddingCostCents(1000);
        // 1000 * 2.0 / 1_000_000 = 0.002 cents
        assertEquals(0.002, cost, 0.0001);
    }

    @Test
    @DisplayName("Embedding cost: 1M tokens = $0.02 = 2 cents")
    void embeddingCostOneMillion() {
        double cost = calculator.embeddingCostCents(1_000_000);
        assertEquals(2.0, cost, 0.001);
    }

    @Test
    @DisplayName("Embedding cost: zero tokens = zero cost")
    void embeddingCostZero() {
        assertEquals(0.0, calculator.embeddingCostCents(0), 0.0001);
    }

    @Test
    @DisplayName("LLM cost: 800 input + 500 output tokens")
    void llmCostBasic() {
        double cost = calculator.llmCostCents(800, 500);
        // input:  800 * 15.0 / 1_000_000 = 0.012 cents
        // output: 500 * 60.0 / 1_000_000 = 0.030 cents
        // total: 0.042 cents
        assertEquals(0.042, cost, 0.001);
    }

    @Test
    @DisplayName("LLM cost: 1M input + 1M output = $0.75")
    void llmCostOneMillionEach() {
        double cost = calculator.llmCostCents(1_000_000, 1_000_000);
        // input: 15.0 cents, output: 60.0 cents = 75.0 cents total
        assertEquals(75.0, cost, 0.001);
    }

    @Test
    @DisplayName("Rerank cost: 500 calls = $0.50")
    void rerankCostBasic() {
        double cost = calculator.rerankCostCents(500);
        // 500 * 100.0 / 1000 = 50.0 cents
        assertEquals(50.0, cost, 0.001);
    }

    @Test
    @DisplayName("Rerank cost: 1000 calls = $1.00")
    void rerankCostOneThousand() {
        double cost = calculator.rerankCostCents(1000);
        assertEquals(100.0, cost, 0.001);
    }

    @Test
    @DisplayName("Total cost: typical query (embed + LLM + rerank)")
    void totalCostTypical() {
        // Typical RAG query: embed 800 tokens, LLM 800 in / 500 out, 1 rerank call
        double total = calculator.totalCostCents(800, 800, 500, 1);
        // embedding: 0.0016
        // LLM: 0.042
        // rerank: 0.100
        // total: ~0.1436 cents
        assertEquals(0.1436, total, 0.002);
    }

    @Test
    @DisplayName("Cost breakdown returns all categories")
    void costBreakdownCategories() {
        var breakdown = calculator.costBreakdown(100000, 50000, 25000, 10);
        assertTrue(breakdown.containsKey("embedding_cents"));
        assertTrue(breakdown.containsKey("llm_input_cents"));
        assertTrue(breakdown.containsKey("llm_output_cents"));
        assertTrue(breakdown.containsKey("rerank_cents"));
        assertTrue(breakdown.containsKey("total_cents"));

        // Verify sum matches total
        double sum = breakdown.get("embedding_cents")
            + breakdown.get("llm_input_cents")
            + breakdown.get("llm_output_cents")
            + breakdown.get("rerank_cents");
        assertEquals(breakdown.get("total_cents"), sum, 0.01);
    }

    @Test
    @DisplayName("Cost per query: division by zero returns zero")
    void costPerQueryZero() {
        assertEquals(0.0, calculator.costPerQuery(100, 0), 0.0001);
    }

    @Test
    @DisplayName("Cost per query: basic division")
    void costPerQueryBasic() {
        // 100 cents total / 50 queries = 2.0 cents/query
        assertEquals(2.0, calculator.costPerQuery(100, 50), 0.001);
    }

    @Test
    @DisplayName("All-zero input returns zero cost")
    void allZero() {
        assertEquals(0.0, calculator.totalCostCents(0, 0, 0, 0), 0.0001);
    }
}
