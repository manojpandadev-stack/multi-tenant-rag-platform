package com.docmind.storage;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

/**
 * Zero-infra fallback implementation of {@link DocumentStorageService}:
 * Stage 2 behavior (storage/{base-dir}/{orgId}/{filename} on local disk),
 * preserved behind the same interface — the storage-mode counterpart of the
 * docmind.processing.mode=async fallback from Section 2.
 *
 * Active only when docmind.storage.mode=local (opt-in for local dev without
 * any infrastructure; production and CI default to s3).
 */
@Service
@ConditionalOnProperty(name = "docmind.storage.mode", havingValue = "local")
public class LocalDiskDocumentStorageService implements DocumentStorageService {

    private static final Logger log = LoggerFactory.getLogger(LocalDiskDocumentStorageService.class);

    private final Path baseDir;

    public LocalDiskDocumentStorageService(
            @Value("${docmind.storage.local.base-dir:storage}") String baseDir) {
        this.baseDir = Path.of(baseDir);
    }

    @Override
    public String store(UUID orgId, String storedFilename, byte[] content, String contentType) {
        String key = buildKey(orgId, storedFilename);
        Path target = baseDir.resolve(key);
        try {
            Files.createDirectories(target.getParent());
            Files.write(target, content);
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to store file locally: " + key, e);
        }
        log.debug("Stored file locally: {} ({} bytes)", target, content.length);
        return key;
    }

    @Override
    public byte[] retrieve(String storageKey) {
        try {
            return Files.readAllBytes(baseDir.resolve(storageKey));
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to read file locally: " + storageKey, e);
        }
    }

    @Override
    public void delete(String storageKey) {
        try {
            Files.deleteIfExists(baseDir.resolve(storageKey));
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to delete file locally: " + storageKey, e);
        }
    }
}
