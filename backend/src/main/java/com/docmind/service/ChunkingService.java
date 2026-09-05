package com.docmind.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/**
 * Recursive character text splitter — chunks text while preserving overlap.
 *
 * Strategy: Try to split on paragraph breaks first, then sentence endings,
 * then word boundaries, then character boundaries. This produces semantically
 * coherent chunks rather than arbitrary mid-word cuts.
 *
 * Default config (from Organization entity):
 * - chunkSize: 512 characters (~125 tokens, reasonable for embedding models)
 * - chunkOverlap: 50 characters (~12 tokens, enough for context continuity)
 *
 * Why 512 chars and not 512 tokens:
 * - Token counting is model-dependent; char-based splitting is model-agnostic
 * - 512 chars ≈ 100-150 tokens for English text, well within OpenAI's 8191 limit
 * - Smaller chunks improve retrieval precision (less noise per chunk)
 * - Larger chunks improve context but dilute relevance signals
 *
 * Why not sentence-level splitting:
 * - Recursive splitting preserves paragraph structure when possible
 * - Falls back gracefully for long paragraphs that exceed chunk size
 * - No dependency on NLP sentence detectors (faster, more portable)
 */
@Service
public class ChunkingService {

    private static final Logger log = LoggerFactory.getLogger(ChunkingService.class);

    // Split delimiters in order of preference (most semantic first)
    private static final String[] DELIMITERS = {"\n\n", "\n", ". ", " ", ""};

    public record Chunk(String text, int chunkIndex, int pageNumber) {}

    /**
     * Split extracted pages into chunks with overlap.
     *
     * @param pages          extracted pages from TextExtractionService
     * @param chunkSize      max characters per chunk
     * @param chunkOverlap   overlap between consecutive chunks
     * @return list of chunks with metadata
     */
    public List<Chunk> chunkPages(List<TextExtractionService.ExtractedPage> pages,
                                   int chunkSize, int chunkOverlap) {
        List<Chunk> allChunks = new ArrayList<>();
        int chunkIndex = 0;

        for (TextExtractionService.ExtractedPage page : pages) {
            List<String> pageChunks = splitRecursively(page.text(), chunkSize, chunkOverlap);
            for (String chunkText : pageChunks) {
                if (!chunkText.isBlank()) {
                    allChunks.add(new Chunk(chunkText.trim(), chunkIndex++, page.pageNumber()));
                }
            }
        }

        log.debug("Chunked into {} chunks (chunkSize={}, overlap={})",
                allChunks.size(), chunkSize, chunkOverlap);
        return allChunks;
    }

    /**
     * Recursive character splitting with overlap.
     * Tries each delimiter from most to least semantic.
     */
    private List<String> splitRecursively(String text, int chunkSize, int overlap) {
        if (text.length() <= chunkSize) {
            return List.of(text);
        }

        List<String> result = new ArrayList<>();
        String remaining = text;

        while (remaining.length() > chunkSize) {
            // Find the best split point
            int splitIndex = findSplitPoint(remaining, chunkSize);

            String chunk = remaining.substring(0, splitIndex).trim();
            if (!chunk.isEmpty()) {
                result.add(chunk);
            }

            // Move forward, accounting for overlap
            int nextStart = Math.max(1, splitIndex - overlap);
            remaining = remaining.substring(nextStart).trim();
        }

        // Add the remainder
        if (!remaining.isEmpty()) {
            result.add(remaining);
        }

        return result;
    }

    /**
     * Find the best split point within the chunk size limit.
     * Tries delimiters in order (paragraph → sentence → word → char).
     */
    private int findSplitPoint(String text, int chunkSize) {
        for (String delimiter : DELIMITERS) {
            if (delimiter.isEmpty()) {
                // Character-level fallback
                return chunkSize;
            }

            // Find the last occurrence of this delimiter within chunkSize
            int lastIndex = text.lastIndexOf(delimiter, chunkSize);
            if (lastIndex > 0) {
                return lastIndex + delimiter.length();
            }
        }

        return chunkSize;
    }

    /**
     * Estimate token count (rough: 1 token ≈ 4 characters for English).
     * Used for cost estimation, not for splitting.
     */
    public static int estimateTokens(String text) {
        return Math.max(1, text.length() / 4);
    }
}
