package com.docmind.config;

import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.model.openai.OpenAiEmbeddingModel;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * LangChain4j configuration.
 *
 * Embedding model: OpenAI text-embedding-3-small
 * - 1536 dimensions, $0.02/1M tokens input
 * - Good balance of cost and quality for RAG
 * - Swappable via config: change embedding.model and embedding.api-key
 *
 * At scale, consider:
 * - Azure OpenAI for lower latency (private endpoints)
 * - Voyage AI or Cohere for domain-specific embeddings
 * - Local models (jina-embeddings-v2-base-en) for cost reduction
 */
@Configuration
public class LangChain4jConfig {

    @Value("${embedding.api-key:}")
    private String apiKey;

    @Value("${embedding.model:text-embedding-3-small}")
    private String model;

    @Bean
    public EmbeddingModel embeddingModel() {
        if (apiKey == null || apiKey.isBlank()) {
            // Return a no-op model for tests / local dev without API key
            return new NoOpEmbeddingModel();
        }
        return OpenAiEmbeddingModel.builder()
            .apiKey(apiKey)
            .modelName(model)
            .build();
    }
}
