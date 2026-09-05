package com.docmind.service;

import org.junit.jupiter.api.*;

import java.util.Random;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for SemanticCacheService cosine similarity logic.
 * No DB, no Redis — pure math verification.
 */
class SemanticCacheServiceTest {

    @Test
    @DisplayName("Identical vectors produce similarity of 1.0")
    void identicalVectors() {
        float[] a = {1.0f, 2.0f, 3.0f, 4.0f};
        float[] b = {1.0f, 2.0f, 3.0f, 4.0f};
        assertEquals(1.0, SemanticCacheService.cosineSimilarity(a, b), 1e-10);
    }

    @Test
    @DisplayName("Orthogonal vectors produce similarity of 0.0")
    void orthogonalVectors() {
        float[] a = {1.0f, 0.0f};
        float[] b = {0.0f, 1.0f};
        assertEquals(0.0, SemanticCacheService.cosineSimilarity(a, b), 1e-10);
    }

    @Test
    @DisplayName("Opposite vectors produce similarity of -1.0")
    void oppositeVectors() {
        float[] a = {1.0f, 2.0f, 3.0f};
        float[] b = {-1.0f, -2.0f, -3.0f};
        assertEquals(-1.0, SemanticCacheService.cosineSimilarity(a, b), 1e-10);
    }

    @Test
    @DisplayName("Null vectors return 0.0")
    void nullVectors() {
        assertEquals(0.0, SemanticCacheService.cosineSimilarity(null, new float[]{1.0f}));
        assertEquals(0.0, SemanticCacheService.cosineSimilarity(new float[]{1.0f}, null));
        assertEquals(0.0, SemanticCacheService.cosineSimilarity(null, null));
    }

    @Test
    @DisplayName("Different-length vectors return 0.0")
    void differentLengths() {
        float[] a = {1.0f, 2.0f};
        float[] b = {1.0f, 2.0f, 3.0f};
        assertEquals(0.0, SemanticCacheService.cosineSimilarity(a, b), 1e-10);
    }

    @Test
    @DisplayName("Zero vector returns 0.0")
    void zeroVector() {
        float[] a = {0.0f, 0.0f, 0.0f};
        float[] b = {1.0f, 2.0f, 3.0f};
        assertEquals(0.0, SemanticCacheService.cosineSimilarity(a, b), 1e-10);
    }

    @Test
    @DisplayName("isSimilar respects threshold correctly")
    void isSimilarThreshold() {
        float[] a = {1.0f, 0.0f, 0.0f};
        float[] b = {1.0f, 0.5f, 0.0f}; // Moderately similar (~89% cosine)

        assertTrue(SemanticCacheService.isSimilar(a, b, 0.85));
        assertFalse(SemanticCacheService.isSimilar(a, b, 0.95));
    }

    @Test
    @DisplayName("Normalized vectors with small angle are similar")
    void normalizedSimilar() {
        // Unit vectors at ~10 degree angle
        double angle = Math.toRadians(10);
        float[] a = {1.0f, 0.0f, 0.0f};
        float[] b = {(float) Math.cos(angle), (float) Math.sin(angle), 0.0f};

        double sim = SemanticCacheService.cosineSimilarity(a, b);
        assertTrue(sim > 0.98, "10-degree angle should be very similar: " + sim);
        assertTrue(SemanticCacheService.isSimilar(a, b, 0.95));
    }

    @Test
    @DisplayName("High-dimensional random vectors have low similarity")
    void randomHighDim() {
        float[] a = new float[1536];
        float[] b = new float[1536];
        java.util.Random rng = new Random(42);
        for (int i = 0; i < 1536; i++) {
            a[i] = rng.nextFloat() * 2 - 1;
            b[i] = rng.nextFloat() * 2 - 1;
        }
        double sim = SemanticCacheService.cosineSimilarity(a, b);
        // Random high-dim vectors should have similarity near 0
        assertTrue(Math.abs(sim) < 0.1,
            "Random 1536-dim vectors should be near-orthogonal: " + sim);
    }

    @Test
    @DisplayName("Very similar high-dimensional vectors trigger cache hit")
    void similarHighDim() {
        float[] a = new float[1536];
        float[] b = new float[1536];
        java.util.Random rng = new Random(42);
        for (int i = 0; i < 1536; i++) {
            a[i] = rng.nextFloat() * 2 - 1;
            b[i] = a[i] + (float)(rng.nextGaussian() * 0.01); // Very small perturbation
        }
        double sim = SemanticCacheService.cosineSimilarity(a, b);
        assertTrue(sim > 0.99, "Nearly identical vectors should be very similar: " + sim);
        assertTrue(SemanticCacheService.isSimilar(a, b, 0.95));
    }
}
