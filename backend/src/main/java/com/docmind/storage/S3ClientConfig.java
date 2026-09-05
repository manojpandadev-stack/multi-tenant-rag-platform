package com.docmind.storage;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;

import java.net.URI;

/**
 * AWS SDK v2 S3 client configuration — active only when
 * docmind.storage.mode=s3 (the S3DocumentStorageService depends on it).
 *
 * LocalStack vs real S3 is a CONFIG-ONLY switch:
 * - docmind.storage.s3.endpoint set (e.g. http://localstack:4566)
 *     -> endpoint override + path-style addressing + static test creds
 *        (LocalStack accepts any non-empty credentials; defaults to test/test)
 * - endpoint empty
 *     -> real AWS: region-derived endpoint, virtual-hosted style, and the
 *        default AWS credentials chain (env vars, IAM role, ~/.aws).
 * No LocalStack URL is ever hardcoded in production code paths.
 */
@Configuration
@ConditionalOnProperty(name = "docmind.storage.mode", havingValue = "s3")
public class S3ClientConfig {

    @Bean
    public S3Client s3Client(
            @Value("${docmind.storage.s3.region:us-east-1}") String region,
            @Value("${docmind.storage.s3.endpoint:}") String endpoint,
            @Value("${docmind.storage.s3.access-key-id:}") String accessKeyId,
            @Value("${docmind.storage.s3.secret-access-key:}") String secretAccessKey) {

        var builder = S3Client.builder().region(Region.of(region))
            // Explicit sync HTTP client: avoids the SDK's SPI auto-discovery,
            // which can pick another HTTP impl lying around on the classpath
            // with an incompatible version (observed: apache-client picked up
            // a stale httpclient5 and blew up with NoClassDefFoundError).
            .httpClient(UrlConnectionHttpClient.create());

        if (endpoint != null && !endpoint.isBlank()) {
            builder.endpointOverride(URI.create(endpoint))
                   .forcePathStyle(true); // LocalStack requires path-style
            builder.credentialsProvider(StaticCredentialsProvider.create(
                AwsBasicCredentials.create(
                    accessKeyId != null && !accessKeyId.isBlank() ? accessKeyId : "test",
                    secretAccessKey != null && !secretAccessKey.isBlank() ? secretAccessKey : "test")));
        }
        // else: real AWS — default credentials chain + regional endpoint

        return builder.build();
    }
}
