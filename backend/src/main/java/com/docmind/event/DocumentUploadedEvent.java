package com.docmind.event;

import java.time.Instant;
import java.util.UUID;

/**
 * Trigger event published when a document is successfully uploaded.
 *
 * Minimal by design — this is a trigger, not a data payload. The consumer
 * re-reads the {@link com.docmind.model.Document} row (the source of truth)
 * and runs the existing extraction/chunking/embedding/storage pipeline.
 */
public record DocumentUploadedEvent(
        UUID documentId,
        UUID orgId,
        Instant uploadedAt) {
}