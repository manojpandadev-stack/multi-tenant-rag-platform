package com.docmind.event;

import com.docmind.model.Document;
import com.docmind.repository.DocumentRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/**
 * Publishes {@link DocumentUploadedEvent} to the {@code document-uploaded} topic.
 *
 * Ordering guarantee: when the upload still has an active transaction, the event
 * is published in {@link TransactionSynchronization#afterCommit()}, so the consumer
 * can never observe an event before the Document row is visible. Outside a
 * transaction (tests, direct publishes) it publishes immediately.
 *
 * If the publish itself fails, the document is marked FAILED rather than being
 * stuck in PENDING forever — the client sees a durable state it can act on.
 */
@Component
public class DocumentUploadedEventPublisher {

    public static final String TOPIC = "document-uploaded";
    private static final Logger log = LoggerFactory.getLogger(DocumentUploadedEventPublisher.class);
    private static final long SEND_TIMEOUT_SECONDS = 10;

    private final KafkaTemplate<String, Object> kafkaTemplate;
    private final DocumentRepository documentRepository;

    public DocumentUploadedEventPublisher(KafkaTemplate<String, Object> kafkaTemplate,
                                          DocumentRepository documentRepository) {
        this.kafkaTemplate = kafkaTemplate;
        this.documentRepository = documentRepository;
    }

    public void publishUploaded(UUID documentId, UUID orgId) {
        DocumentUploadedEvent event = new DocumentUploadedEvent(documentId, orgId, Instant.now());

        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    doPublish(event);
                }
            });
        } else {
            doPublish(event);
        }
    }

    private void doPublish(DocumentUploadedEvent event) {
        try {
            kafkaTemplate.send(TOPIC, event.documentId().toString(), event)
                    .get(SEND_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            log.info("Published DocumentUploadedEvent for document {} to topic {}",
                    event.documentId(), TOPIC);
        } catch (Exception e) {
            log.error("Failed to publish DocumentUploadedEvent for document {}: {}",
                    event.documentId(), e.getMessage());
            documentRepository.findById(event.documentId()).ifPresent(doc -> {
                doc.setStatus(Document.ProcessingStatus.FAILED);
                doc.setErrorMessage("Publish to Kafka failed: " + e.getMessage());
                documentRepository.save(doc);
            });
        }
    }
}