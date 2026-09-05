package com.docmind.service;

import com.docmind.config.EmbeddingConfig;
import com.docmind.config.ObservabilityMetrics;
import com.docmind.model.Document;
import com.docmind.model.DocumentChunk;
import com.docmind.model.Organization;
import com.docmind.repository.DocumentChunkRepository;
import com.docmind.repository.DocumentRepository;
import com.docmind.repository.OrganizationRepository;
import io.micrometer.observation.annotation.Observed;
import io.micrometer.tracing.Span;
import io.micrometer.tracing.Tracer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Async document processing pipeline.
 *
 * Pipeline stages:
 * 1. Extract text from file (PDFBox / POI / plain read)
 * 2. Split text into chunks (recursive character splitter)
 * 3. Embed chunks via LangChain4j (batched, with retry)
 * 4. Store chunks in pgvector with embeddings
 *
 * Status transitions: PENDING → PROCESSING → READY (or FAILED)
 * On failure, the document retains its file and org_id for retry.
 *
 * Thread safety: Runs on a dedicated bounded thread pool (documentProcessingExecutor).
 * Each invocation is transactional at the DB level (status updates + chunk inserts).
 */
@Service
public class DocumentProcessingPipeline {

    private static final Logger log = LoggerFactory.getLogger(DocumentProcessingPipeline.class);

    private final DocumentRepository documentRepository;
    private final DocumentChunkRepository chunkRepository;
    private final OrganizationRepository orgRepository;
    private final TextExtractionService extractionService;
    private final ChunkingService chunkingService;
    private final EmbeddingService embeddingService;
    private final EmbeddingConfig embeddingConfig;    private final SemanticCacheService cacheService;
    private final UsageRecordingService usageRecording;
    private final ObservabilityMetrics metrics;
    private final Tracer tracer;


    public DocumentProcessingPipeline(
            DocumentRepository documentRepository,
            DocumentChunkRepository chunkRepository,
            OrganizationRepository orgRepository,
            TextExtractionService extractionService,
            ChunkingService chunkingService,
            EmbeddingService embeddingService,
            EmbeddingConfig embeddingConfig,
            SemanticCacheService cacheService,
            UsageRecordingService usageRecording,
            ObservabilityMetrics metrics,
            Tracer tracer) {
        this.documentRepository = documentRepository;
        this.chunkRepository = chunkRepository;
        this.orgRepository = orgRepository;
        this.extractionService = extractionService;
        this.chunkingService = chunkingService;
        this.embeddingService = embeddingService;
        this.embeddingConfig = embeddingConfig;
        this.cacheService = cacheService;
        this.usageRecording = usageRecording;
        this.metrics = metrics;
        this.tracer = tracer;
    }

    /**
     * Process a document asynchronously.
     * Called from the upload endpoint — returns immediately, processing runs in background.
     */
    @Async("documentProcessingExecutor")
    @Transactional
    public void processDocument(UUID documentId) {
        // Re-fetch inside the async context (new transaction)
        Document doc = documentRepository.findById(documentId).orElse(null);
        if (doc == null) {
            log.error("Document {} not found — cannot process", documentId);
            return;
        }

        UUID orgId = doc.getOrgId();
        MDC.put("org_id", orgId.toString());
        MDC.put("document_id", documentId.toString());

        // Create a child span for this pipeline stage
        Span pipelineSpan = tracer.nextSpan().name("document-process").start();
        try (Tracer.SpanInScope ws = tracer.withSpan(pipelineSpan)) {
            pipelineSpan.tag("org_id", orgId.toString());
            pipelineSpan.tag("document_id", documentId.toString());
            pipelineSpan.tag("filename", doc.getFilename());

            log.info("Starting processing for document {} (org={}, file={})",
                    documentId, orgId, doc.getFilename());

            try {
                // Transition to PROCESSING
                doc.setStatus(Document.ProcessingStatus.PROCESSING);
                doc.setErrorMessage(null);
                documentRepository.save(doc);

                // Stage 1: Text extraction
                Path filePath = Path.of(doc.getStoragePath());
                var extractionResult = extractionService.extract(filePath, doc.getFileType());
            if (!extractionResult.success()) {
                failDocument(doc, extractionResult.errorMessage());
                return;
            }

            // Stage 2: Chunking
            Organization org = orgRepository.findById(orgId).orElseThrow();
            var chunks = chunkingService.chunkPages(
                extractionResult.pages(),
                org.getChunkSize(),
                org.getChunkOverlap()
            );

            if (chunks.isEmpty()) {
                failDocument(doc, "No extractable text content found in document");
                return;
            }

            // Stage 3: Embedding (batched)
            List<String> texts = chunks.stream()
                .map(ChunkingService.Chunk::text)
                .toList();

            var embeddingResult = embeddingService.embedBatch(texts);

            // Stage 4: Store chunks in DB
            List<DocumentChunk> dbChunks = new ArrayList<>();
            for (int i = 0; i < chunks.size(); i++) {
                ChunkingService.Chunk chunk = chunks.get(i);
                float[] vector = embeddingResult.vectors().get(i);

                DocumentChunk dbChunk = DocumentChunk.builder()
                    .organization(doc.getOrganization())
                    .document(doc)
                    .content(chunk.text())
                    .chunkIndex(chunk.chunkIndex())
                    .tokenCount(ChunkingService.estimateTokens(chunk.text()))
                    .embeddingStatus(vector != null
                        ? DocumentChunk.EmbeddingStatus.DONE
                        : DocumentChunk.EmbeddingStatus.PENDING)
                    .embedding(vector)  // null if embedding failed
                    .metadataJson("{\"page\":" + chunk.pageNumber()
                        + ",\"chunk_size\":" + chunk.text().length() + "}")
                    .build();

                dbChunks.add(dbChunk);
            }

            chunkRepository.saveAll(dbChunks);

            // Mark document as READY
            doc.setStatus(Document.ProcessingStatus.READY);
            doc.setChunkCount(dbChunks.size());
            documentRepository.save(doc);

            log.info("Document {} processed successfully: {} chunks, ~{} tokens consumed",
                    documentId, dbChunks.size(), embeddingResult.tokensConsumed());

            // Record embedding token usage
            usageRecording.recordEmbeddingTokens(orgId, embeddingResult.tokensConsumed());
            metrics.recordEmbeddingTokens(embeddingResult.tokensConsumed());
            pipelineSpan.tag("chunks.count", String.valueOf(dbChunks.size()));
            pipelineSpan.tag("tokens.consumed", String.valueOf(embeddingResult.tokensConsumed()));
            pipelineSpan.tag("status", "READY");

            } catch (Exception e) {
                log.error("Processing failed for document {}: {}", documentId, e.getMessage(), e);
                pipelineSpan.tag("status", "FAILED");
                pipelineSpan.tag("error.message", e.getMessage());
                failDocument(doc, "Processing error: " + e.getMessage());
            } finally {
                pipelineSpan.end();
                MDC.remove("org_id");
                MDC.remove("document_id");
            }
        }
    }

    /**
     * Retry processing of a previously failed document.
     * Re-runs the full pipeline with the same file.
     */
    @Async("documentProcessingExecutor")
    @Transactional
    public void retryDocument(UUID documentId) {
        Document doc = documentRepository.findById(documentId).orElse(null);
        if (doc == null) {
            log.error("Document {} not found — cannot retry", documentId);
            return;
        }

        if (doc.getStatus() != Document.ProcessingStatus.FAILED) {
            log.warn("Document {} is not in FAILED status (current: {}) — skipping retry",
                    documentId, doc.getStatus());
            return;
        }

        log.info("Retrying processing for document {}", documentId);

        // Clean up any partial chunks from previous attempts
        UUID orgId = doc.getOrgId();
        var existingChunks = chunkRepository.findByDocumentIdAndOrgId(documentId, orgId);
        if (!existingChunks.isEmpty()) {
            chunkRepository.deleteAll(existingChunks);
        }

        // Invalidate stale cache entries referencing this document
        cacheService.invalidateByDocument(documentId);

        doc.setChunkCount(0);
        documentRepository.save(doc);

        // Re-run the pipeline
        processDocument(documentId);
    }

    private void failDocument(Document doc, String errorMessage) {
        doc.setStatus(Document.ProcessingStatus.FAILED);
        doc.setErrorMessage(errorMessage);
        documentRepository.save(doc);
        log.warn("Document {} marked FAILED: {}", doc.getId(), errorMessage);
    }
}
