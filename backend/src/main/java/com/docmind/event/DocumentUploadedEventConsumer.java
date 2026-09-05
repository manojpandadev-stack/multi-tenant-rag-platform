package com.docmind.event;

import com.docmind.model.Document;
import com.docmind.repository.DocumentRepository;
import com.docmind.service.DocumentProcessingPipeline;
import io.micrometer.tracing.Span;
import io.micrometer.tracing.Tracer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

import java.util.UUID;

/**
 * Consumes {@link DocumentUploadedEvent}s and delegates each document to the
 * existing extract → chunk → embed → store pipeline.
 *
 * The pipeline logic is intentionally NOT duplicated here — this class only
 * looks up the Document row (the source of truth) and hands off to
 * {@link DocumentProcessingPipeline#processDocument(UUID)}.
 *
 * Failure behavior: the pipeline marks the document FAILED with the error
 * reason (identical to the {@code @Async} path). A message whose document row
 * is missing is logged and acknowledged — this can only happen if the upload
 * transaction rolled back after publish, which the afterCommit ordering in
 * {@link DocumentUploadedEventPublisher} prevents.
 *
 * Only active when {@code docmind.processing.mode=kafka}; the {@code @Async}
 * fallback (Stage 2) remains available as the alternate trigger.
 */
@Component
@ConditionalOnProperty(name = "docmind.processing.mode", havingValue = "kafka")
public class DocumentUploadedEventConsumer {

    private static final Logger log = LoggerFactory.getLogger(DocumentUploadedEventConsumer.class);
    private static final String CONSUMER_GROUP = "docmind-processing-group";

    private final DocumentRepository documentRepository;
    private final DocumentProcessingPipeline pipeline;
    private final Tracer tracer;

    public DocumentUploadedEventConsumer(DocumentRepository documentRepository,
                                         DocumentProcessingPipeline pipeline,
                                         Tracer tracer) {
        this.documentRepository = documentRepository;
        this.pipeline = pipeline;
        this.tracer = tracer;
    }

    @KafkaListener(topics = DocumentUploadedEventPublisher.TOPIC, groupId = CONSUMER_GROUP)
    public void onDocumentUploaded(DocumentUploadedEvent event) {
        Span span = tracer.currentSpan();
        String traceId = span != null ? span.context().traceId() : "none";
        log.info("Kafka event consumed for document {} (org {}) — traceId={}",
                event.documentId(), event.orgId(), traceId);

        UUID documentId = event.documentId();
        if (documentId == null) {
            log.warn("Received DocumentUploadedEvent with null documentId — skipping");
            return;
        }

        Document doc = documentRepository.findById(documentId).orElse(null);
        if (doc == null) {
            // Publish happens after commit, so this shouldn't happen — but never crash the
            // consumer thread if it does (row deleted/rolled back after publish).
            log.warn("Document {} not found — skipping Kafka event (upload rolled back?)", documentId);
            return;
        }

        if (doc.getStatus() != Document.ProcessingStatus.PENDING) {
            log.info("Document {} is in state {} (expected PENDING) — skipping (idempotent consume)",
                    documentId, doc.getStatus());
            return;
        }

        // Hand off to the bounded-pool pipeline. The task decorator in AsyncConfig
        // preserves the trace context that the Kafka listener extracted from headers.
        pipeline.processDocument(documentId);
    }
}