package com.docmind.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Provides the pgvector dimension at runtime, so the entity's @Column(columnDefinition)
 * matches whatever the Liquibase changeset created.
 *
 * In production: vector(1536) for text-embedding-3-small
 * In tests:      vector(5) for fast, lightweight test vectors
 */
@Component("embeddingConfig")
public class EmbeddingConfig {

    @Value("${embedding.dimension:1536}")
    private int dimension;

    public String vectorColumnDefinition() {
        return "vector(" + dimension + ")";
    }

    public int getDimension() {
        return dimension;
    }
}
