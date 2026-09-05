package com.docmind.ingestion;

import com.docmind.model.*;
import com.docmind.repository.*;
import com.docmind.service.DocumentService;
import com.docmind.tenant.TenantContext;
import com.docmind.testutil.TestDataCleaner;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration test for the full document ingestion pipeline.
 *
 * Tests the flow: upload → extract → chunk → embed → store
 * Uses Testcontainers with pgvector for real database testing.
 * Uses NoOpEmbeddingModel (random vectors) since no OpenAI key in tests.
 */
@SpringBootTest
@Testcontainers
@Tag("integration")
@TestPropertySource(properties = "docmind.processing.mode=async")
class DocumentIngestionTest {

    @Container
    static PostgreSQLContainer<?> pg = new PostgreSQLContainer<>("pgvector/pgvector:pg16")
        .withDatabaseName("docmind_test")
        .withUsername("test")
        .withPassword("test");

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", pg::getJdbcUrl);
        registry.add("spring.datasource.username", pg::getUsername);
        registry.add("spring.datasource.password", pg::getPassword);
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "create-drop");
        registry.add("spring.sql.init.mode", () -> "always");
        registry.add("spring.liquibase.enabled", () -> "false");
    }

    @Autowired private OrganizationRepository orgRepository;
    @Autowired private UserRepository userRepository;
    @Autowired private DocumentRepository documentRepository;
    @Autowired private DocumentChunkRepository chunkRepository;
    @Autowired private TestDataCleaner cleaner;
    @Autowired private DocumentService documentService;
    @Autowired private PasswordEncoder passwordEncoder;

    @Autowired
    private org.springframework.jdbc.core.JdbcTemplate jdbc;

    private Organization org;
    private User user;
    private UUID userId;

    @BeforeEach
    void setUp() {
        // FK-safe cleanup via shared utility
        cleaner.deleteAll();

        // Create org and user
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

        // Set tenant context (normally done by TenantFilter from JWT)
        TenantContext.setOrgId(org.getId());
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    @Test
    @DisplayName("Upload TXT file → chunks created with embeddings, all scoped to org")
    void uploadTxtFileCreatesChunks() throws IOException {
        // Read the test fixture
        Path testFile = Path.of("src/test/resources/fixtures/test-document.txt");
        byte[] content = Files.readAllBytes(testFile);

        MockMultipartFile file = new MockMultipartFile(
            "file", "test-document.txt", "text/plain", content);

        // Upload
        var uploadResponse = documentService.uploadDocument(file, userId);
        assertNotNull(uploadResponse.id());
        assertEquals("PENDING", uploadResponse.status());

        // Wait for async processing (poll with timeout)
        UUID docId = UUID.fromString(uploadResponse.id());
        awaitProcessingComplete(docId, 30);

        // Verify document is READY
        var docResponse = documentService.getDocument(docId, userId);
        assertEquals("READY", docResponse.status());
        assertTrue(docResponse.chunkCount() > 0, "Should have created chunks");

        // Verify chunks are scoped to the org
        var chunks = chunkRepository.findByDocumentIdAndOrgId(docId, org.getId());
        assertEquals(docResponse.chunkCount(), chunks.size());

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

    @Test
    @DisplayName("Upload rejected for unsupported file type")
    void rejectUnsupportedFileType() {
        MockMultipartFile file = new MockMultipartFile(
            "file", "test.exe", "application/octet-stream", "binary data".getBytes());

        assertThrows(IllegalArgumentException.class,
            () -> documentService.uploadDocument(file, userId));
    }

    @Test
    @DisplayName("Upload rejected for empty file")
    void rejectEmptyFile() {
        MockMultipartFile file = new MockMultipartFile(
            "file", "empty.txt", "text/plain", new byte[0]);

        assertThrows(IllegalArgumentException.class,
            () -> documentService.uploadDocument(file, userId));
    }

    @Test
    @DisplayName("Upload rejected for oversized file")
    void rejectOversizedFile() {
        byte[] bigContent = new byte[26 * 1024 * 1024]; // 26MB
        MockMultipartFile file = new MockMultipartFile(
            "file", "big.txt", "text/plain", bigContent);

        assertThrows(IllegalArgumentException.class,
            () -> documentService.uploadDocument(file, userId));
    }

    @Test
    @DisplayName("Tenant isolation: async processing never leaks chunks across orgs")
    void asyncProcessingRespectsTenantIsolation() throws IOException {
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

        // Wait for both to finish
        awaitProcessingComplete(docAId, 30);
        awaitProcessingComplete(docBId, 30);

        // Verify isolation: Org A's chunks don't include Org B's
        var chunksA = chunkRepository.findByDocumentIdAndOrgId(docAId, org.getId());
        var chunksB = chunkRepository.findByDocumentIdAndOrgId(docBId, orgB.getId());

        assertFalse(chunksA.isEmpty(), "Org A should have chunks");
        assertFalse(chunksB.isEmpty(), "Org B should have chunks");

        for (var chunk : chunksA) {
            assertEquals(org.getId(), chunk.getOrgId(),
                "Org A chunks must have org A's ID");
        }
        for (var chunk : chunksB) {
            assertEquals(orgB.getId(), chunk.getOrgId(),
                "Org B chunks must have org B's ID");
        }
    }

    /**
     * Poll document status until READY or FAILED, with timeout.
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

        fail("Document " + docId + " did not complete processing within " + timeoutSeconds + "s");
    }
}
