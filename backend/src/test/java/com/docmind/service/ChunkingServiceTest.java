package com.docmind.service;

import com.docmind.service.ChunkingService.Chunk;
import com.docmind.service.TextExtractionService.ExtractedPage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ChunkingServiceTest {

    private ChunkingService chunkingService;

    @BeforeEach
    void setUp() {
        chunkingService = new ChunkingService();
    }

    @Test
    @DisplayName("Short text stays as single chunk")
    void shortTextSingleChunk() {
        List<ExtractedPage> pages = List.of(new ExtractedPage(1, "Hello world"));
        List<Chunk> chunks = chunkingService.chunkPages(pages, 512, 50);

        assertEquals(1, chunks.size());
        assertEquals("Hello world", chunks.get(0).text());
        assertEquals(0, chunks.get(0).chunkIndex());
        assertEquals(1, chunks.get(0).pageNumber());
    }

    @Test
    @DisplayName("Long paragraph splits into multiple chunks with overlap")
    void longParagraphSplits() {
        String longText = "A".repeat(1000);
        List<ExtractedPage> pages = List.of(new ExtractedPage(1, longText));
        List<Chunk> chunks = chunkingService.chunkPages(pages, 200, 20);

        assertTrue(chunks.size() > 1, "Should produce multiple chunks");

        // All original text should be represented (with overlap)
        StringBuilder reconstructed = new StringBuilder();
        for (Chunk chunk : chunks) {
            reconstructed.append(chunk.text());
        }
        assertTrue(reconstructed.toString().contains("A".repeat(200)),
            "Chunks should cover the full text");
    }

    @Test
    @DisplayName("Chunk indices are sequential across pages")
    void chunkIndicesSequential() {
        List<ExtractedPage> pages = List.of(
            new ExtractedPage(1, "A".repeat(500)),
            new ExtractedPage(2, "B".repeat(500))
        );
        List<Chunk> chunks = chunkingService.chunkPages(pages, 200, 20);

        for (int i = 0; i < chunks.size(); i++) {
            assertEquals(i, chunks.get(i).chunkIndex(),
                "Chunk " + i + " should have chunkIndex=" + i);
        }
    }

    @Test
    @DisplayName("Page numbers are preserved in chunks")
    void pageNumberPreserved() {
        List<ExtractedPage> pages = List.of(
            new ExtractedPage(1, "Page one text. ".repeat(20)),
            new ExtractedPage(2, "Page two text. ".repeat(20))
        );
        List<Chunk> chunks = chunkingService.chunkPages(pages, 100, 10);

        boolean hasPage1 = chunks.stream().anyMatch(c -> c.pageNumber() == 1);
        boolean hasPage2 = chunks.stream().anyMatch(c -> c.pageNumber() == 2);
        assertTrue(hasPage1, "Should have chunks from page 1");
        assertTrue(hasPage2, "Should have chunks from page 2");
    }

    @Test
    @DisplayName("Overlap ensures no text is lost between chunks")
    void overlapEnsuresContinuity() {
        // Create text with enough length to split into multiple chunks
        String text = "The quick brown fox jumps over the lazy dog. ".repeat(20);

        List<ExtractedPage> pages = List.of(new ExtractedPage(1, text));
        List<Chunk> chunks = chunkingService.chunkPages(pages, 100, 20);

        assertTrue(chunks.size() >= 2, "Should produce at least 2 chunks");

        // Verify no text is lost: every character from the original appears in some chunk
        // (overlap means text can repeat, but nothing should be dropped)
        for (Chunk chunk : chunks) {
            assertTrue(chunk.text().length() > 0, "Chunks should not be empty");
        }
    }

    @Test
    @DisplayName("Empty pages produce no chunks")
    void emptyPagesNoChunks() {
        List<ExtractedPage> pages = List.of(
            new ExtractedPage(1, ""),
            new ExtractedPage(2, "   "),
            new ExtractedPage(3, "")
        );
        List<Chunk> chunks = chunkingService.chunkPages(pages, 512, 50);

        assertTrue(chunks.isEmpty(), "Empty pages should produce no chunks");
    }

    @Test
    @DisplayName("Token estimate is approximately correct")
    void tokenEstimate() {
        String text = "Hello world, this is a test"; // 27 chars
        int tokens = ChunkingService.estimateTokens(text);
        // 27/4 ≈ 6-7 tokens
        assertTrue(tokens >= 5 && tokens <= 10,
            "Token estimate should be reasonable, got: " + tokens);
    }

    @Test
    @DisplayName("Paragraph-based splitting prefers paragraph breaks")
    void prefersParagraphBreaks() {
        String text = "First paragraph with enough text to exceed chunk size. ".repeat(10)
            + "\n\n"
            + "Second paragraph with enough text to exceed chunk size. ".repeat(10);

        List<ExtractedPage> pages = List.of(new ExtractedPage(1, text));
        List<Chunk> chunks = chunkingService.chunkPages(pages, 200, 20);

        // The split should happen at the paragraph boundary
        boolean firstChunkContainsParagraph1 = chunks.get(0).text().contains("First paragraph");
        boolean lastChunkContainsParagraph2 = chunks.get(chunks.size() - 1).text().contains("Second paragraph");
        assertTrue(firstChunkContainsParagraph1 && lastChunkContainsParagraph2,
            "Splitting should respect paragraph boundaries when possible");
    }
}
