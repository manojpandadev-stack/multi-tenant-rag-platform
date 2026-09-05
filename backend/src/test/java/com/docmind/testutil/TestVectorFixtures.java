package com.docmind.testutil;

import java.util.Arrays;
import java.util.Random;

/**
 * Shared test utility for generating embedding vectors.
 *
 * All vectors are 1536-dimensional to match the production entity definition
 * (text-embedding-3-small output dimension).
 *
 * Usage in tests:
 *   .embedding(TestVectorFixtures.randomEmbedding())       // random, for general use
 *   .embedding(TestVectorFixtures.uniformEmbedding(0.5f))  // all same value, for isolation tests
 *   .embedding(TestVectorFixtures.seededEmbedding(42))     // deterministic, for reproducible tests
 */
public final class TestVectorFixtures {

    /** Matches the production embedding dimension (text-embedding-3-small). */
    public static final int DIMENSION = 1536;

    private TestVectorFixtures() {}

    /**
     * Generate a random 1536-dim vector with values in [-1, 1].
     */
    public static float[] randomEmbedding() {
        Random rng = new Random();
        float[] v = new float[DIMENSION];
        for (int i = 0; i < DIMENSION; i++) {
            v[i] = rng.nextFloat() * 2f - 1f;
        }
        return v;
    }

    /**
     * Generate a deterministic 1536-dim vector from a seed.
     * Same seed always produces the same vector — useful for assertions.
     */
    public static float[] seededEmbedding(long seed) {
        Random rng = new Random(seed);
        float[] v = new float[DIMENSION];
        for (int i = 0; i < DIMENSION; i++) {
            v[i] = rng.nextFloat() * 2f - 1f;
        }
        return v;
    }

    /**
     * Generate a 1536-dim vector where every element is the same value.
     * Useful for tenant isolation tests that need identical embeddings
     * across different orgs to prove they don't leak.
     */
    public static float[] uniformEmbedding(float value) {
        float[] v = new float[DIMENSION];
        Arrays.fill(v, value);
        return v;
    }
}
