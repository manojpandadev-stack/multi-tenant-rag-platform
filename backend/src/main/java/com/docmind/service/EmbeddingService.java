package com.docmind.service;

import dev.langchain4j.data.document.Metadata;
import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.model.output.Response;
import io.github.resilience4j.retry.annotation.Retry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Embedding service using LangChain4j's embedding model abstraction.
 *
 * Batches chunks to minimize API calls (default batch size: 20).
 * OpenAI text-embedding-3-small costs $0.02/1M tokens — batching 20 chunks
 * per call reduces overhead and stays well within rate limits.
 *
 * Rate limit handling:
 * - resilience4j retry with exponential backoff (3 attempts, 1s/2s/4s delays)
 * - On final failure, chunks are left as PENDING for later retry
 */
@Service
public class EmbeddingService {

    private static final Logger log = LoggerFactory.getLogger(EmbeddingService.class);

    private final EmbeddingModel embeddingModel;
    private final int batchSize;

    public EmbeddingService(
            EmbeddingModel embeddingModel,
            @Value("${embedding.batch-size:20}") int batchSize) {
        this.embeddingModel = embeddingModel;
        this.batchSize = batchSize;
    }

    public record EmbeddingResult(List<float[]> vectors, int tokensConsumed) {}

    /**
     * Embed a batch of text segments, returning vectors in the same order.
     * Processes in sub-batches of {@code batchSize} to stay within API limits.
     */
    @Retry(name = "embedding", fallbackMethod = "embeddingFallback")
    public EmbeddingResult embedBatch(List<String> texts) {
        List<float[]> allVectors = new ArrayList<>();
        int totalTokens = 0;

        for (int i = 0; i < texts.size(); i += batchSize) {
            int end = Math.min(i + batchSize, texts.size());
            List<String> batch = texts.subList(i, end);

            int batchIndex = i / batchSize;
            List<TextSegment> segments = batch.stream()
                .map(text -> TextSegment.from(text, Metadata.from("batch_index", String.valueOf(batchIndex))))
                .toList();

            Response<List<Embedding>> response = embeddingModel.embedAll(segments);

            for (Embedding embedding : response.content()) {
                allVectors.add(embedding.vector());
            }

            // Rough token estimate for cost tracking
            for (String text : batch) {
                totalTokens += ChunkingService.estimateTokens(text);
            }

            log.debug("Embedded batch {}/{}: {} chunks, ~{} tokens",
                    (i / batchSize) + 1, (int) Math.ceil((double) texts.size() / batchSize),
                    batch.size(), totalTokens);
        }

        return new EmbeddingResult(allVectors, totalTokens);
    }

    /**
     * Fallback when embedding fails after all retries.
     * Returns null vectors so chunks stay in PENDING status for retry.
     */
    private EmbeddingResult embeddingFallback(List<String> texts, Throwable t) {
        log.error("Embedding failed after retries: {}. Chunks will remain PENDING for retry.", t.getMessage());
        List<float[]> nullVectors = new ArrayList<>();
        for (int i = 0; i < texts.size(); i++) {
            nullVectors.add(null);
        }
        return new EmbeddingResult(nullVectors, 0);
    }
}
