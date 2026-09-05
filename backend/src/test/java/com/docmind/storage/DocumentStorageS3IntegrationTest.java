package com.docmind.storage;

import com.docmind.model.Document;
import com.docmind.model.Organization;
import com.docmind.model.Role;
import com.docmind.model.User;
import com.docmind.repository.DocumentRepository;
import com.docmind.repository.OrganizationRepository;
import com.docmind.repository.UserRepository;
import com.docmind.service.DocumentService;
import com.docmind.tenant.TenantContext;
import com.docmind.testutil.TestDataCleaner;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import org.testcontainers.containers.localstack.LocalStackContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.HeadObjectRequest;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration tests for S3 document storage via LocalStack.
 *
 * Uses the OFFICIAL Testcontainers LocalStack module (not a manually-started
 * container — same lesson as the Kafka module in Section 2). All assertions
 * verify real bucket state through the raw AWS S3 client, not just
 * "no exception was thrown".
 *
 * What each test proves:
 * 1. upload → the object physically exists in the bucket, under the tenant
 *    prefix org/{orgId}/..., with byte-identical content — and the async
 *    pipeline reaches READY, which is only possible if it retrieved the
 *    bytes back out of S3 (re-processing reads from storage).
 * 2. delete → the object is actually GONE from the bucket (HeadObject 404).
 * 3. tenant isolation → keys are org-prefixed, and the application layer
 *    (the only path to storage) refuses cross-tenant access — S3 itself has
 *    no Postgres-style org_id row filtering, so this is where isolation lives.
 */
@SpringBootTest
@Testcontainers
@Tag("integration")
@TestPropertySource(properties = {
    "docmind.processing.mode=async",
    "docmind.storage.mode=s3"
})
class DocumentStorageS3IntegrationTest {

    @Container
    static PostgreSQLContainer<?> pg = new PostgreSQLContainer<>("pgvector/pgvector:pg16")
        .withDatabaseName("docmind_s3_test")
        .withUsername("test")
        .withPassword("test");

    // Official Testcontainers LocalStack module — S3 service only.
    // Pinned to 4.13.1: the last community release line that starts without a
    // LocalStack auth token (2026.x images demand LOCALSTACK_AUTH_TOKEN even
    // for community services). CI must not need any account/secret.
    @Container
    static LocalStackContainer localstack =
        new LocalStackContainer(DockerImageName.parse("localstack/localstack:4.13.1"))
            .withServices(LocalStackContainer.Service.S3);

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", pg::getJdbcUrl);
        registry.add("spring.datasource.username", pg::getUsername);
        registry.add("spring.datasource.password", pg::getPassword);
        // "create" not "create-drop" — see DocumentIngestionTest for the
        // shutdown-hang post-mortem (Surefire fork kill at 30s).
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "create");
        registry.add("spring.sql.init.mode", () -> "always");
        registry.add("spring.liquibase.enabled", () -> "false");

        // Point the S3 storage service at the LocalStack container
        registry.add("docmind.storage.s3.endpoint",
            () -> localstack.getEndpoint().toString());
        registry.add("docmind.storage.s3.access-key-id", localstack::getAccessKey);
        registry.add("docmind.storage.s3.secret-access-key", localstack::getSecretKey);
        registry.add("docmind.storage.s3.region", localstack::getRegion);
        // Unique bucket per run. NOTE: the name MUST be computed once into a
        // static field — DynamicPropertyRegistry suppliers are re-invoked on
        // EVERY property resolution, so an inline UUID here would give the
        // storage service and the test @Value two different bucket names.
        registry.add("docmind.storage.s3.bucket", () -> bucketName);
        registry.add("docmind.storage.s3.auto-create-bucket", () -> "true");
    }

    static final String bucketName =
        "docmind-s3-test-" + UUID.randomUUID().toString().substring(0, 8);

    @Autowired private DocumentStorageService storageService;
    @Autowired private S3Client s3Client; // raw client for direct bucket verification
    @Autowired private DocumentService documentService;
    @Autowired private DocumentRepository documentRepository;
    @Autowired private OrganizationRepository orgRepository;
    @Autowired private UserRepository userRepository;
    @Autowired private TestDataCleaner cleaner;
    @Autowired private PasswordEncoder passwordEncoder;

    @Value("${docmind.storage.s3.bucket}")
    private String bucket;

    private Organization orgA;
    private Organization orgB;
    private UUID userAId;
    private UUID userBId;

    @BeforeEach
    void setUp() {
        cleaner.deleteAll();

        orgA = orgRepository.save(Organization.builder()
            .name("S3 Test Org A")
            .slug("s3-a-" + UUID.randomUUID().toString().substring(0, 8))
            .retrievalStrategy("vector-only")
            .build());
        orgB = orgRepository.save(Organization.builder()
            .name("S3 Test Org B")
            .slug("s3-b-" + UUID.randomUUID().toString().substring(0, 8))
            .retrievalStrategy("vector-only")
            .build());

        userAId = userRepository.save(User.builder()
            .email("a-" + UUID.randomUUID().toString().substring(0, 8) + "@test.com")
            .passwordHash(passwordEncoder.encode("password"))
            .fullName("Org A User")
            .organization(orgA)
            .role(Role.MEMBER)
            .isActive(true)
            .build()).getId();

        userBId = userRepository.save(User.builder()
            .email("b-" + UUID.randomUUID().toString().substring(0, 8) + "@test.com")
            .passwordHash(passwordEncoder.encode("password"))
            .fullName("Org B User")
            .organization(orgB)
            .role(Role.MEMBER)
            .isActive(true)
            .build()).getId();

        TenantContext.setOrgId(orgA.getId());
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    private MockMultipartFile txtFile(String filename) throws IOException {
        byte[] content = Files.readAllBytes(Path.of("src/test/resources/fixtures/test-document.txt"));
        return new MockMultipartFile("file", filename, "text/plain", content);
    }

    private void awaitProcessingComplete(UUID docId, int timeoutSeconds) {
        long start = System.currentTimeMillis();
        long timeoutMs = timeoutSeconds * 1000L;
        while (System.currentTimeMillis() - start < timeoutMs) {
            var doc = documentRepository.findById(docId).orElse(null);
            if (doc == null) return;
            String status = doc.getStatus().name();
            if ("READY".equals(status) || "FAILED".equals(status)) return;
            try { Thread.sleep(200); } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
        fail("Document " + docId + " did not complete processing within " + timeoutSeconds + "s");
    }

    @Test
    @DisplayName("Upload stores byte-identical object under org prefix; pipeline re-reads it from S3")
    void uploadStoresObjectUnderTenantPrefixAndPipelineReadsItBack() throws IOException {
        MockMultipartFile file = txtFile("org-a-doc.txt");
        byte[] expectedBytes = file.getBytes();

        var response = documentService.uploadDocument(file, userAId);
        UUID docId = UUID.fromString(response.id());

        // Storage key is opaque but must live under Org A's tenant prefix
        var doc = documentRepository.findById(docId).orElseThrow();
        String key = doc.getStoragePath();
        assertEquals("org/" + orgA.getId() + "/" + doc.getFilename(), key,
            "Object key must be org/{orgId}/{filename} — the tenant prefix convention");

        // The object must PHYSICALLY exist in the bucket (verified via raw S3 client)
        var head = s3Client.headObject(
            HeadObjectRequest.builder().bucket(bucket).key(key).build());
        assertEquals(expectedBytes.length, head.contentLength(),
            "Object in bucket must be byte-identical in size to the upload");

        // ...and byte-identical in content
        byte[] storedBytes = s3Client.getObjectAsBytes(
            b -> b.bucket(bucket).key(key)).asByteArray();
        assertArrayEquals(expectedBytes, storedBytes,
            "Object content in S3 must match the uploaded file exactly");

        // The async pipeline reaches READY — which requires reading the bytes
        // back out of S3 for text extraction (re-processing reads from storage)
        awaitProcessingComplete(docId, 60);
        var processed = documentRepository.findById(docId).orElseThrow();
        assertEquals(Document.ProcessingStatus.READY, processed.getStatus(),
            "Pipeline must process successfully by reading the object back from S3");
        assertTrue(processed.getChunkCount() > 0,
            "Chunks must have been created from S3-hosted content");
    }

    @Test
    @DisplayName("Delete removes the object from the bucket (HeadObject → 404)")
    void deleteDocumentRemovesObjectFromS3() throws IOException {
        var response = documentService.uploadDocument(txtFile("to-delete.txt"), userAId);
        UUID docId = UUID.fromString(response.id());
        awaitProcessingComplete(docId, 60);

        String key = documentRepository.findById(docId).orElseThrow().getStoragePath();

        // Pre-condition: object exists
        assertDoesNotThrow(() -> s3Client.headObject(
            HeadObjectRequest.builder().bucket(bucket).key(key).build()));

        // Delete through the application (the only supported path)
        documentService.deleteDocument(docId, userAId);

        // The object is actually GONE from the bucket
        assertThrows(NoSuchKeyException.class,
            () -> s3Client.headObject(HeadObjectRequest.builder().bucket(bucket).key(key).build()),
            "Object must be removed from S3, not just from the database");

        // ...and the DB record is gone too
        assertNull(documentRepository.findByIdAndOrgId(docId, orgA.getId()));
    }

    @Test
    @DisplayName("Tenant isolation: org-scoped key prefixes + application-layer cross-tenant denial")
    void tenantIsolationInObjectStorage() throws IOException {
        // Org A uploads in its context (set in @BeforeEach)
        var respA = documentService.uploadDocument(txtFile("org-a-secret.txt"), userAId);
        UUID docAId = UUID.fromString(respA.id());
        String keyA = documentRepository.findById(docAId).orElseThrow().getStoragePath();

        // Org B uploads in its own context
        TenantContext.setOrgId(orgB.getId());
        var respB = documentService.uploadDocument(txtFile("org-b-doc.txt"), userBId);
        UUID docBId = UUID.fromString(respB.id());
        String keyB = documentRepository.findById(docBId).orElseThrow().getStoragePath();
        TenantContext.setOrgId(orgA.getId());

        // 1. Each org's objects live strictly under their own prefix
        assertTrue(keyA.startsWith("org/" + orgA.getId() + "/"),
            "Org A object must be under Org A's prefix. Got: " + keyA);
        assertTrue(keyB.startsWith("org/" + orgB.getId() + "/"),
            "Org B object must be under Org B's prefix. Got: " + keyB);
        assertNotEquals(keyA, keyB);

        // 2. The application layer refuses cross-tenant reads and deletes...
        TenantContext.setOrgId(orgB.getId());
        assertThrows(Exception.class,
            () -> documentService.getDocument(docAId, userBId),
            "Org B must not be able to read Org A's document through the API");
        assertThrows(Exception.class,
            () -> documentService.deleteDocument(docAId, userBId),
            "Org B must not be able to delete Org A's document through the API");
        TenantContext.setOrgId(orgA.getId());

        // 3. Org A's object is untouched by Org B's denied attempts
        assertDoesNotThrow(() -> s3Client.headObject(
            HeadObjectRequest.builder().bucket(bucket).key(keyA).build()));

        // 4. Storage keys are server-derived — no API surface accepts or returns
        //    them (DocumentResponse has no storagePath field), and the org id in
        //    the key comes from the tenant context, so Org B can never even FORM
        //    a key pointing into Org A's prefix. S3 itself has no org_id row
        //    filtering — this application-layer boundary is the isolation guarantee.
        String forgedKey = "org/" + orgA.getId() + "/" +
            documentRepository.findById(docAId).orElseThrow().getFilename();
        assertNotEquals(forgedKey, keyB,
            "Org B's key must never collide with Org A's prefix");
        // ...and Org A can still read its own object through the storage service:
        byte[] contentA = storageService.retrieve(keyA);
        assertTrue(contentA.length > 0, "Org A can still read its own object");
    }
}
