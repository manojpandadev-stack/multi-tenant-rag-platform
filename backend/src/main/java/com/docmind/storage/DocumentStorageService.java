package com.docmind.storage;

import java.util.UUID;

/**
 * Storage abstraction for uploaded document files.
 *
 * Replaces the Stage 2 local-disk write of storage/{org_id}/ with a swappable
 * interface: S3-compatible object storage in production and CI (LocalStack),
 * local disk as a zero-infra fallback — selected by docmind.storage.mode,
 * mirroring the docmind.processing.mode (kafka/async) toggle from Section 2.
 *
 * Storage keys are opaque strings persisted in documents.storage_path. The
 * convention (see buildKey) is org/{orgId}/{storedFilename} — every object
 * lives under its tenant's prefix. S3 itself does NOT enforce tenant
 * isolation the way Postgres org_id row filtering does; isolation is
 * enforced at the application layer:
 *   1. Keys are always derived server-side (org id comes from the tenant
 *      context, never from client input).
 *   2. No API response ever exposes storage keys (DocumentResponse has no
 *      storagePath field).
 *   3. documents.storage_path is only ever reached through
 *      findByIdAndOrgId-scoped repository lookups.
 */
public interface DocumentStorageService {

    /**
     * Store file content, scoped to the tenant.
     *
     * @param orgId          tenant org id (from the tenant context, never client input)
     * @param storedFilename server-generated unique filename (UUID.ext)
     * @param content        file bytes
     * @param contentType    MIME type (nullable)
     * @return the opaque storage key to persist in documents.storage_path
     */
    String store(UUID orgId, String storedFilename, byte[] content, String contentType);

    /**
     * Retrieve file bytes by storage key.
     * Throws (implementation-specific) if the object does not exist.
     */
    byte[] retrieve(String storageKey);

    /**
     * Delete the object at the given key. Must be idempotent — deleting a
     * missing object must not throw (mirrors the old Files.deleteIfExists).
     */
    void delete(String storageKey);

    /**
     * Canonical tenant-scoped key layout. A default method so the prefix
     * convention has exactly one source of truth across implementations.
     */
    default String buildKey(UUID orgId, String storedFilename) {
        return "org/" + orgId + "/" + storedFilename;
    }
}
