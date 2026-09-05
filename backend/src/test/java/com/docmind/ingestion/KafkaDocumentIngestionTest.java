package com.docmind.ingestion;

import com.docmind.event.DocumentUploadedEventPublisher;
import com.docmind.model.Document;
import com.docmind.model.Organization;
import com.docmind.model.Role;
import com.docmind.model.User;
import com.docmind.repository.DocumentChunkRepository;
import com.docmind.repository.DocumentRepository;
import com.docmind.repository.OrganizationRepository;
import com.docmind.repository.UserRepository;
import com.docmind.service.DocumentService;
import com.docmind.tenant.TenantContext;
import com.docmind.testutil.TestDataCleaner;
import io.micrometer.tracing.Span;
import io.micrometer.tracing.Tracer;
import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.admin.AdminClientConfig;
import org.apache.kafka.clients.admin.NewTopic;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.kafka.KafkaContainer;
import org.testcontainers.utility.DockerImageName;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration tests for the Kafka-backed document ingestion pipeline.
 *
 * Mirrors the assertions in DocumentIngestionTest but exercises the
 * event-driven path: upload -> publish DocumentUploadedEvent -> Kafka topic
 * -> consumer -> extract -> chunk -> embed -> store.
 *
 * Uses Testcontainers for both PostgreSQL (pgvector) and Kafka â€” the same
 * Testcontainers pattern the rest of the integration suite already relies on.
 *
 * Trace propagation is verified in tracePropagatesAcrossKafkaBoundary
 * by starting a real span on the test thread, uploading within its scope,
 * and asserting the consumer log shows the same trace ID (proving W3C
 * traceparent was injected by the producer and extracted by the consumer).
 */
@SpringBootTest
@Testcontainers
@Tag("integration")
@TestPropertySource(properties = "docmind.processing.mode=kafka")
@ExtendWith(OutputCaptureExtension.class)
class KafkaDocumentIngestionTest {

    @Container
    static PostgreSQLContainer<?> pg = new PostgreSQLContainer<>("pgvector/pgvector:pg16")
            .withDatabaseName("docmind_test")
            .withUsername("test")
            .withPassword("test");

    @Container
    static KafkaContainer kafka = new KafkaContainer(
            DockerImageName.parse("apache/kafka:3.7.1"));

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", pg::getJdbcUrl);
        registry.add("spring.datasource.username", pg::getUsername);
        registry.add("spring.datasource.password", pg::getPassword);
        // "create" (NOT "create-drop"): the drop-at-close phase borrows a Hikari
        // connection after the Testcontainers container is already stopped,
        // blocking 30s (Hikari connectionTimeout) -> Surefire kills the forked
        // JVM -> CI exits 1. Containers are ephemeral, so nothing needs dropping.
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "create");
        registry.add("spring.sql.init.mode", () -> "always");
        registry.add("spring.liquibase.enabled", () -> "false");
        registry.add("spring.kafka.bootstrap-servers", kafka::getBootstrapServers);
    }

    @Autowired private OrganizationRepository orgRepository;
    @Autowired private UserRepository userRepository;
    @Autowired private DocumentRepository documentRepository;
    @Autowired private DocumentChunkRepository chunkRepository;
    @Autowired private TestDataCleaner cleaner;
    @Autowired private DocumentService documentService;
    @Autowired private DocumentUploadedEventPublisher eventPublisher;
    @Autowired private PasswordEncoder passwordEncoder;
    @Autowired private Tracer tracer;

    private Organization org;
    private User user;
    private UUID userId;

    @BeforeAll
    static void createTopic() {
        try (AdminClient admin = AdminClient.create(Map.of(
                AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, kafka.getBootstrapServers()))) {
            try {
                admin.createTopics(List.of(
                        new NewTopic("document-uploaded", 1, (short) 1)))
                        .all().get();
            } catch (Exception e) {
                // Topic may already exist from a previous run â€” safe to ignore
            }
        }
    }

    @BeforeEach
    void setUp() {
        cleaner.deleteAll();

        org = orgRepository.save(Organization.builder()
                .name("Test Corp")
                .slug("test-" + UUID.randomUUID().toString().substring(0, 8))
                .build());

        user = userRepository.save(User.builder()
                .email("test@" + UUID.randomUUID().toString().substring(0, 8) + ".com")
                .passwordHash(passwordEncoder.encode("password"))
                .fullName("Test User")
                .organization(org)
                .role(Role.MEMBER)
                .isActive(true)
                .build());

        userId = user.getId();
        TenantContext.setOrgId(org.getId());
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    // â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€
    // Happy path: upload -> Kafka consume -> READY with chunks
    // â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€

    @Test
    @DisplayName("Upload TXT file via Kafka -> document reaches READY with chunks, all scoped to org")
    void kafkaUploadCreatesChunks() throws IOException {
        Path testFile = Path.of("src/test/resources/fixtures/test-document.txt");
        byte[] content = Files.readAllBytes(testFile);
        MockMultipartFile file = new MockMultipartFile(
                "file", "test-document.txt", "text/plain", content);

        var uploadResponse = documentService.uploadDocument(file, userId);
        assertNotNull(uploadResponse.id());
        assertEquals("PENDING", uploadResponse.status(),
                "Upload should return PENDING - processing is async via Kafka");

        UUID docId = UUID.fromString(uploadResponse.id());
        awaitProcessingComplete(docId, 30);

        // Document should be READY
        Document doc = documentRepository.findById(docId).orElseThrow();
        assertEquals(Document.ProcessingStatus.READY, doc.getStatus());
        assertTrue(doc.getChunkCount() > 0, "Should have created chunks");

        // Chunks must all be scoped to Org A
        var chunks = chunkRepository.findByDocumentIdAndOrgId(docId, org.getId());
        assertEquals(doc.getChunkCount(), chunks.size(),
                "Chunk count in DB should match document's chunkCount");

        for (var chunk : chunks) {
            assertEquals(org.getId(), chunk.getOrgId(),
                    "Every chunk must be scoped to the correct org");
            assertNotNull(chunk.getContent());
            assertFalse(chunk.getContent().isBlank());
            assertNotNull(chunk.getEmbedding(),
                    "Embedding should be non-null (NoOp model generates random vectors)");
            assertEquals(1536, chunk.getEmbedding().length,
                    "Embedding should be 1536-dimensional");
                        assertNotNull(chunk.getEmbeddingStatus());
        }
    }

    // ──────────────────────────────────────────────────────────────
    // Tenant isolation: cross-org data never leaks
    // ──────────────────────────────────────────────────────────────

    @Test
    @DisplayName("Kafka processing respects tenant isolation — Org A cannot see Org B's chunks")
    void kafkaProcessingRespectsTenantIsolation() throws IOException {
        // Create a second org
        Organization orgB = orgRepository.save(Organization.builder()
                .name("Other Corp")
                .slug("other-" + UUID.randomUUID().toString().substring(0, 8))
                .build());

        User userB = userRepository.save(User.builder()
                .email("other@" + UUID.randomUUID().toString().substring(0, 8) + ".com")
                .passwordHash(passwordEncoder.encode("password"))
                .fullName("Other User")
                .organization(orgB)
                .role(Role.MEMBER)
                .isActive(true)
                .build());

        // Upload to Org A
        Path testFile = Path.of("src/test/resources/fixtures/test-document.txt");
        byte[] content = Files.readAllBytes(testFile);
        MockMultipartFile fileA = new MockMultipartFile(
                "file", "org-a-doc.txt", "text/plain", content);
        var responseA = documentService.uploadDocument(fileA, userId);
        UUID docAId = UUID.fromString(responseA.id());

        // Upload to Org B (switch tenant context)
        TenantContext.setOrgId(orgB.getId());
        MockMultipartFile fileB = new MockMultipartFile(
                "file", "org-b-doc.txt", "text/plain", content);
        var responseB = documentService.uploadDocument(fileB, userB.getId());
        UUID docBId = UUID.fromString(responseB.id());

        // Switch back to Org A for assertions
        TenantContext.setOrgId(org.getId());

        // Wait for both to complete
        awaitProcessingComplete(docAId, 30);
        awaitProcessingComplete(docBId, 30);

        // Both should have chunks
        var chunksA = chunkRepository.findByDocumentIdAndOrgId(docAId, org.getId());
        var chunksB = chunkRepository.findByDocumentIdAndOrgId(docBId, orgB.getId());
        assertFalse(chunksA.isEmpty(), "Org A should have chunks");
        assertFalse(chunksB.isEmpty(), "Org B should have chunks");

        // Org A chunks must all belong to Org A
        for (var chunk : chunksA) {
            assertEquals(org.getId(), chunk.getOrgId(),
                    "Org A chunks must have org A's ID");
        }

        // Org B chunks must all belong to Org B
        for (var chunk : chunksB) {
            assertEquals(orgB.getId(), chunk.getOrgId(),
                    "Org B chunks must have org B's ID");
        }

        // Cross-check: querying Org B's doc with Org A's context returns nothing
        var crossCheck = chunkRepository.findByDocumentIdAndOrgId(docBId, org.getId());
        assertTrue(crossCheck.isEmpty(),
                "Org A must never see Org B's chunks");
    }

    // ──────────────────────────────────────────────────────────────
    // Failure path: corrupt file -> FAILED with error message
    // ──────────────────────────────────────────────────────────────

    @Test
    @DisplayName("Kafka consumer handles processing failure — document marked FAILED with error")
    void kafkaProcessingFailureMarksDocumentFailed() {
        // Create a document pointing at a non-existent storage path.
        // The pipeline will try to read it, fail, and mark FAILED.
        Document doc = Document.builder()
                .organization(org)
                .filename("nonexistent.txt")
                .originalFilename("nonexistent.txt")
                .fileType("TXT")
                .fileSizeBytes(100L)
                .storagePath("storage/nonexistent/path/file.txt")
                .status(Document.ProcessingStatus.PENDING)
                .build();
        doc = documentRepository.save(doc);

        // Publish the event directly (no transaction → publishes immediately)
        eventPublisher.publishUploaded(doc.getId(), org.getId());

        // Wait for FAILED (poll, same pattern as awaitProcessingComplete)
        long start = System.currentTimeMillis();
        long timeoutMs = 15_000L;
        while (System.currentTimeMillis() - start < timeoutMs) {
            var refreshed = documentRepository.findById(doc.getId()).orElse(null);
            if (refreshed != null && refreshed.getStatus() == Document.ProcessingStatus.FAILED) {
                break;
            }
            try { Thread.sleep(200); } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                fail("Interrupted while waiting for FAILED status");
            }
        }

        Document processed = documentRepository.findById(doc.getId()).orElseThrow();
        assertEquals(Document.ProcessingStatus.FAILED, processed.getStatus(),
                "Document with non-existent file should be marked FAILED");
        assertNotNull(processed.getErrorMessage(),
                "Error message should be populated");
        assertTrue(
                processed.getErrorMessage().toLowerCase().contains("failed to read")
                || processed.getErrorMessage().toLowerCase().contains("no such file")
                || processed.getErrorMessage().toLowerCase().contains("processing error"),
                "Error message should reference the file-read failure. Got: "
                                        + processed.getErrorMessage());
    }

    // ──────────────────────────────────────────────────────────────
    // Trace propagation: span context survives the Kafka boundary
    // ──────────────────────────────────────────────────────────────

    @Test
    @DisplayName("Trace context propagates from upload thread across Kafka to consumer")
    void tracePropagatesAcrossKafkaBoundary(CapturedOutput output) throws IOException {
        // Start a real span on the test thread using the OTel-backed Tracer.
        Span span = tracer.nextSpan().name("test-upload").start();
        String expectedTraceId = span.context().traceId();
        assertNotNull(expectedTraceId);
        assertFalse(expectedTraceId.equals("0000000000000000"),
                "OTel tracer should generate a non-zero trace ID");

        try (Tracer.SpanInScope ws = tracer.withSpan(span)) {
            // Upload within the span scope — Kafka producer observation
            // injects the W3C traceparent into record headers.
            Path testFile = Path.of("src/test/resources/fixtures/test-document.txt");
            byte[] content = Files.readAllBytes(testFile);
            MockMultipartFile file = new MockMultipartFile(
                    "file", "trace-test.txt", "text/plain", content);
            var uploadResponse = documentService.uploadDocument(file, userId);
            UUID docId = UUID.fromString(uploadResponse.id());

            // Wait for the consumer to finish processing.
            awaitProcessingComplete(docId, 30);
        } finally {
            span.end();
        }

        // The consumer logs: "Kafka event consumed for document {} (org {}) — traceId={}"
        // If propagation worked, the logged traceId equals expectedTraceId (not "none").
        String allOutput = output.getOut();
        assertTrue(allOutput.contains(expectedTraceId),
                "Trace ID " + expectedTraceId + " should appear in Kafka consumer log.\n"
                        + "Captured stdout:\n" + allOutput);
        assertFalse(allOutput.contains("traceId=none"),
                "Consumer should have extracted a non-'none' trace ID.\n"
                        + "If 'none' appears, headers were not propagated.\n"
                        + "Captured stdout:\n" + allOutput);
    }

    // ──────────────────────────────────────────────────────────────
    // Helper: poll document status until terminal state
    // ──────────────────────────────────────────────────────────────

    /**
     * Polls document status until READY or FAILED, with timeout.
     * Same pattern as DocumentIngestionTest.awaitProcessingComplete.
     */
    private void awaitProcessingComplete(UUID docId, int timeoutSeconds) {
        long start = System.currentTimeMillis();
        long timeoutMs = timeoutSeconds * 1000L;

        while (System.currentTimeMillis() - start < timeoutMs) {
            var doc = documentRepository.findById(docId).orElse(null);
            if (doc == null) return;

            String status = doc.getStatus().name();
            if ("READY".equals(status) || "FAILED".equals(status)) {
                return;
            }

            try {
                Thread.sleep(200);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }

        Document finalDoc = documentRepository.findById(docId).orElse(null);
        fail("Document " + docId + " did not complete processing within "
                + timeoutSeconds + "s. Last status: "
                + (finalDoc != null ? finalDoc.getStatus().name() : "null(deleted)"));
    }
}
