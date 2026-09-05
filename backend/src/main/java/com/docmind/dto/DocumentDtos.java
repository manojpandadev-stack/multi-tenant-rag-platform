package com.docmind.dto;

import java.util.List;

public class DocumentDtos {

    public record DocumentResponse(
        String id,
        String filename,
        String fileType,
        Long fileSizeBytes,
        String status,
        Integer chunkCount,
        String errorMessage,
        String createdAt
    ) {
        // Convenience constructor without errorMessage (for backward compat)
        public DocumentResponse(String id, String filename, String fileType,
                                Long fileSizeBytes, String status, Integer chunkCount, String createdAt) {
            this(id, filename, fileType, fileSizeBytes, status, chunkCount, null, createdAt);
        }
    }

    public record UploadResponse(
        String id,
        String filename,
        String status,
        String message
    ) {}

    public record PaginatedResponse<T>(
        List<T> content,
        int page,
        int size,
        long totalElements,
        int totalPages
    ) {}

    public record QueryRequest(
        String question,
        String documentId,
        Integer topK
    ) {}

    public record QueryResponse(
        String answer,
        List<RetrievedChunk> retrievedChunks,
        boolean cacheHit,
        long latencyMs,
        Integer tokensConsumed
    ) {}

    public record RetrievedChunk(
        String chunkId,
        String documentId,
        String content,
        double score,
        int chunkIndex
    ) {}

    public record UsageResponse(
        int documentsStored,
        long storageMB,
        long totalQueries,
        long tokensConsumed,
        long cacheHits,
        double cacheHitRate,
        long estimatedCostCents
    ) {}
}
