package com.docmind.config;

import dev.langchain4j.data.document.Metadata;
import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.model.output.Response;

import java.util.List;
import java.util.Random;

/**
 * No-op embedding model that generates random vectors.
 * Used for tests and local dev when no OpenAI API key is configured.
 * Produces 1536-dim random vectors to match the production schema.
 */
public class NoOpEmbeddingModel implements EmbeddingModel {

    private static final int DIMENSION = 1536;
    private final Random random = new Random();

    @Override
    public Response<List<Embedding>> embedAll(List<TextSegment> segments) {
        List<Embedding> embeddings = segments.stream()
            .map(segment -> {
                float[] vector = new float[DIMENSION];
                for (int i = 0; i < DIMENSION; i++) {
                    vector[i] = random.nextFloat() * 2f - 1f;
                }
                return Embedding.from(vector);
            })
            .toList();
        return Response.from(embeddings);
    }
}
