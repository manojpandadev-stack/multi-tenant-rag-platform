package com.docmind.storage;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.CreateBucketRequest;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.HeadBucketRequest;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.S3Exception;

import jakarta.annotation.PostConstruct;
import java.util.UUID;

/**
 * S3-compatible implementation of {@link DocumentStorageService}.
 *
 * Uses the AWS SDK v2 sync S3 client. The client bean (S3ClientConfig) is
 * built with an endpoint override when docmind.storage.s3.endpoint is set —
 * that is the ONLY thing that changes between LocalStack (local dev / CI)
 * and real AWS S3 (production): same code, config-only switch.
 *
 * Tenant isolation: keys are always org/{orgId}/... with the org id taken
 * from the server-side tenant context. S3 has no row-level org_id filtering
 * like Postgres, so application-layer access control (see interface javadoc)
 * is what actually prevents cross-tenant access.
 */
@Service
@ConditionalOnProperty(name = "docmind.storage.mode", havingValue = "s3")
public class S3DocumentStorageService implements DocumentStorageService {

    private static final Logger log = LoggerFactory.getLogger(S3DocumentStorageService.class);

    private final S3Client s3Client;
    private final String bucket;
    private final boolean autoCreateBucket;

    public S3DocumentStorageService(
            S3Client s3Client,
            @Value("${docmind.storage.s3.bucket:docmind-documents}") String bucket,
            @Value("${docmind.storage.s3.auto-create-bucket:false}") boolean autoCreateBucket) {
        this.s3Client = s3Client;
        this.bucket = bucket;
        this.autoCreateBucket = autoCreateBucket;
    }

    /**
     * LocalStack/dev convenience: create the bucket on startup if missing.
     * Disabled by default so production never requires s3:CreateBucket.
     */
    @PostConstruct
    void ensureBucket() {
        if (!autoCreateBucket) {
            return;
        }
        try {
            s3Client.headBucket(HeadBucketRequest.builder().bucket(bucket).build());
        } catch (S3Exception e) {
            if (e.statusCode() == 404 || e.statusCode() == 400) {
                log.info("Bucket '{}' not found — creating it", bucket);
                s3Client.createBucket(CreateBucketRequest.builder().bucket(bucket).build());
            } else {
                throw e;
            }
        }
        log.info("S3 storage ready: bucket='{}'", bucket);
    }

    @Override
    public String store(UUID orgId, String storedFilename, byte[] content, String contentType) {
        String key = buildKey(orgId, storedFilename);
        s3Client.putObject(
            PutObjectRequest.builder()
                .bucket(bucket)
                .key(key)
                .contentType(contentType)
                .build(),
            RequestBody.fromBytes(content));
        log.debug("Stored object s3://{}/{} ({} bytes)", bucket, key, content.length);
        return key;
    }

    @Override
    public byte[] retrieve(String storageKey) {
        return s3Client.getObjectAsBytes(
                GetObjectRequest.builder().bucket(bucket).key(storageKey).build())
            .asByteArray();
    }

    @Override
    public void delete(String storageKey) {
        // S3 deletes of non-existent keys succeed — idempotent by design,
        // matching the old Files.deleteIfExists semantics.
        s3Client.deleteObject(
            DeleteObjectRequest.builder().bucket(bucket).key(storageKey).build());
        log.debug("Deleted object s3://{}/{}", bucket, storageKey);
    }
}
