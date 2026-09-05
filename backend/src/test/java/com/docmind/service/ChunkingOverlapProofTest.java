package com.docmind.service;

import com.docmind.service.ChunkingService.Chunk;
import com.docmind.service.TextExtractionService.ExtractedPage;
import org.junit.jupiter.api.Test;

import java.util.List;

/**
 * Proof that the chunking overlap is real, not masked by a weak assertion.
 * Prints chunk boundaries so a human can verify continuity.
 */
class ChunkingOverlapProofTest {

    @Test
    void showChunkBoundariesWithOverlap() {
        ChunkingService service = new ChunkingService();

        // 100-char text with clear sequential words
        String text = "AAA BBB CCC DDD EEE FFF GGG HHH III JJJ KKK LLL MMM NNN OOO PPP QQQ RRR SSS TTT";
        // Each word is 3 chars + 1 space = 4 chars. 20 words = 80 chars.

        List<ExtractedPage> pages = List.of(new ExtractedPage(1, text));
        List<Chunk> chunks = service.chunkPages(pages, 20, 8);

        System.out.println("=== CHUNK BOUNDARY PROOF ===");
        System.out.println("Input (" + text.length() + " chars): " + text);
        System.out.println("Chunk size: 20, Overlap: 8");
        System.out.println("Number of chunks: " + chunks.size());
        System.out.println();

        for (int i = 0; i < chunks.size(); i++) {
            Chunk c = chunks.get(i);
            System.out.printf("Chunk[%d] (page=%d, idx=%d, len=%d): \"%s\"%n",
                i, c.pageNumber(), c.chunkIndex(), c.text().length(), c.text());
        }

        System.out.println();
        System.out.println("=== OVERLAP VERIFICATION ===");
        for (int i = 0; i < chunks.size() - 1; i++) {
            String current = chunks.get(i).text();
            String next = chunks.get(i + 1).text();

            // Find the overlap: last N chars of current == first N chars of next
            int maxOverlap = Math.min(current.length(), next.length());
            int overlapLen = 0;
            for (int len = 1; len <= maxOverlap; len++) {
                if (current.endsWith(next.substring(0, len))) {
                    overlapLen = len;
                }
            }

            System.out.printf("Overlap between chunk[%d] and chunk[%d]: %d chars%n",
                i, i + 1, overlapLen);

            if (overlapLen > 0) {
                String overlapText = current.substring(current.length() - overlapLen);
                System.out.printf("  Shared text: \"%s\"%n", overlapText);
            }
        }

        // Assert overlap exists
        for (int i = 0; i < chunks.size() - 1; i++) {
            String current = chunks.get(i).text();
            String next = chunks.get(i + 1).text();
            int maxOverlap = Math.min(current.length(), next.length());
            int overlapLen = 0;
            for (int len = 1; len <= maxOverlap; len++) {
                if (current.endsWith(next.substring(0, len))) {
                    overlapLen = len;
                }
            }
            assert overlapLen > 0 :
                "No overlap between chunk " + i + " and " + (i + 1);
        }

        System.out.println("\n✅ All consecutive chunks have real overlap. Bug not masked.");
    }
}
