package com.docmind.service;

import com.docmind.service.ReciprocalRankFusion.FusedResult;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class ReciprocalRankFusionTest {

    @Test
    @DisplayName("Document in both lists gets higher score than document in one list")
    void documentInBothListsScoresHigher() {
        // A is rank 1 in vector, rank 3 in BM25
        // B is rank 1 in BM25 only
        List<String> vector = List.of("A", "C", "D");
        List<String> bm25 = List.of("B", "A", "E");

        var results = ReciprocalRankFusion.fuse(vector, bm25, 60.0, 10);

        assertFalse(results.isEmpty());
        // A appears in both lists → should be ranked first
        assertEquals("A", results.get(0).document());
        assertEquals(Set.of("vector", "bm25"), results.get(0).sources());
    }

    @Test
    @DisplayName("Rank 1 in one list beats rank 5 in another")
    void rank1BeatsRank5() {
        // X is rank 1 in BM25 but not in vector
        // Y is rank 5 in vector but not in BM25
        List<String> vector = List.of("Z", "W", "V", "U", "Y");
        List<String> bm25 = List.of("X");

        var results = ReciprocalRankFusion.fuse(vector, bm25, 60.0, 10);

        // X (rank 1 BM25) should beat Y (rank 5 vector)
        int xIndex = -1, yIndex = -1;
        for (int i = 0; i < results.size(); i++) {
            if (results.get(i).document().equals("X")) xIndex = i;
            if (results.get(i).document().equals("Y")) yIndex = i;
        }
        assertTrue(xIndex >= 0, "X should be in results");
        assertTrue(yIndex >= 0, "Y should be in results");
        assertTrue(xIndex < yIndex, "Rank 1 BM25 (X) should beat rank 5 vector (Y)");
    }

    @Test
    @DisplayName("Max results limits output size")
    void maxResultsLimitsOutput() {
        List<String> vector = List.of("A", "B", "C", "D", "E");
        List<String> bm25 = List.of("F", "G", "H", "I", "J");

        var results = ReciprocalRankFusion.fuse(vector, bm25, 60.0, 3);

        assertEquals(3, results.size(), "Should return exactly 3 results");
    }

    @Test
    @DisplayName("Empty inputs produce empty output")
    void emptyInputsProduceEmptyOutput() {
        var results = ReciprocalRankFusion.fuse(List.of(), List.of());
        assertTrue(results.isEmpty());
    }

    @Test
    @DisplayName("Single list input works correctly")
    void singleListInput() {
        List<String> vector = List.of("A", "B", "C");
        var results = ReciprocalRankFusion.fuse(vector, List.of());

        assertEquals(3, results.size());
        assertEquals("A", results.get(0).document());
        assertEquals(Set.of("vector"), results.get(0).sources());
    }

    @Test
    @DisplayName("Identical lists produce consistent ordering")
    void identicalListsProduceConsistentOrder() {
        List<String> list = List.of("A", "B", "C", "D");
        var results = ReciprocalRankFusion.fuse(list, list);

        assertEquals(4, results.size());
        for (int i = 0; i < results.size(); i++) {
            assertEquals(list.get(i), results.get(i).document());
            assertEquals(Set.of("vector", "bm25"), results.get(i).sources());
        }
    }

    @Test
    @DisplayName("RRF scores are monotonically decreasing")
    void scoresMonotonicallyDecreasing() {
        List<String> vector = List.of("A", "B", "C", "D", "E");
        List<String> bm25 = List.of("E", "D", "C", "B", "A");

        var results = ReciprocalRankFusion.fuse(vector, bm25, 60.0, 10);

        for (int i = 1; i < results.size(); i++) {
            assertTrue(results.get(i - 1).rrfScore() >= results.get(i).rrfScore(),
                "Scores should be monotonically decreasing at index " + i);
        }
    }

    @Test
    @DisplayName("Match type correctly identifies source")
    void matchTypeIdentification() {
        List<String> vector = List.of("A", "B");       // A and B found by vector
        List<String> bm25 = List.of("B", "C");         // B and C found by BM25

        var results = ReciprocalRankFusion.fuse(vector, bm25, 60.0, 10);

        for (var r : results) {
            if (r.document().equals("A")) {
                assertEquals(Set.of("vector"), r.sources());
            } else if (r.document().equals("B")) {
                assertEquals(Set.of("vector", "bm25"), r.sources());
            } else if (r.document().equals("C")) {
                assertEquals(Set.of("bm25"), r.sources());
            }
        }
    }

    @Test
    @DisplayName("Default k=60 produces expected scores for rank 1")
    void defaultKProducesExpectedScores() {
        List<String> vector = List.of("A");
        var results = ReciprocalRankFusion.fuse(vector, List.of());

        // score = 1 / (60 + 1) = 1/61 ≈ 0.01639
        double expected = 1.0 / 61.0;
        assertEquals(expected, results.get(0).rrfScore(), 1e-10);
    }
}
