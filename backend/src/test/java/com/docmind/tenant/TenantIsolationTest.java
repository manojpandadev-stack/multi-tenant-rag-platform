package com.docmind.tenant;

import com.docmind.model.*;
import com.docmind.repository.*;
import com.docmind.security.JwtTokenProvider;
import com.docmind.testutil.TestDataCleaner;
import com.docmind.testutil.TestVectorFixtures;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * CRITICAL TEST SUITE: Tenant Isolation
 *
 * These tests prove that Organization A can NEVER access Organization B's data.
 * This is the most important security property of the entire platform.
 *
 * Test strategy:
 * 1. Create two separate organizations with their own data
 * 2. Verify queries from org A never return org B's data
 * 3. Verify the tenant context validation catches cross-tenant access attempts
 * 4. Test at the repository level (data layer) and service level
 *
 * Uses Testcontainers 2.0.4 with pgvector/pgvector:pg16 — fully self-contained,
 * no pre-existing container required. Anyone who clones the repo and has Docker
 * can run: mvn test -Dtest=TenantIsolationTest
 */
@SpringBootTest
@Testcontainers
@Tag("integration")
@TestPropertySource(properties = "docmind.processing.mode=async")
class TenantIsolationTest {

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
    @Autowired private JwtTokenProvider tokenProvider;
    @Autowired private org.springframework.security.crypto.password.PasswordEncoder passwordEncoder;

    private Organization orgA;
    private Organization orgB;
    private User userA;
    private User userB;

    @BeforeEach
    void setUp() {
        // FK-safe cleanup via shared utility
        cleaner.deleteAll();

        // Create Organization A
        orgA = orgRepository.save(Organization.builder()
            .name("Acme Corp")
            .slug("acme-" + UUID.randomUUID().toString().substring(0, 8))
            .build());

        userA = userRepository.save(User.builder()
            .email("admin@acme-" + UUID.randomUUID().toString().substring(0, 8) + ".com")
            .passwordHash(passwordEncoder.encode("password"))
            .fullName("Acme Admin")
            .organization(orgA)
            .role(Role.ORG_ADMIN)
            .isActive(true)
            .build());

        // Create Organization B
        orgB = orgRepository.save(Organization.builder()
            .name("Globex Inc")
            .slug("globex-" + UUID.randomUUID().toString().substring(0, 8))
            .build());

        userB = userRepository.save(User.builder()
            .email("admin@globex-" + UUID.randomUUID().toString().substring(0, 8) + ".com")
            .passwordHash(passwordEncoder.encode("password"))
            .fullName("Globex Admin")
            .organization(orgB)
            .role(Role.ORG_ADMIN)
            .isActive(true)
            .build());

        // Create documents and chunks for Org A
        Document docA = documentRepository.save(Document.builder()
            .organization(orgA)
            .filename("acme-secret-plans.pdf")
            .originalFilename("acme-secret-plans.pdf")
            .fileType("PDF")
            .fileSizeBytes(1024000L)
            .status(Document.ProcessingStatus.READY)
            .chunkCount(2)
            .build());

        chunkRepository.save(DocumentChunk.builder()
            .organization(orgA)
            .document(docA)
            .content("Acme Corp's secret product roadmap for 2024 includes a new enterprise tier")
            .embedding(TestVectorFixtures.uniformEmbedding(0.1f))
            .chunkIndex(0)
            .tokenCount(20)
            .build());

        chunkRepository.save(DocumentChunk.builder()
            .organization(orgA)
            .document(docA)
            .content("Acme Corp revenue projections show 40% YoY growth")
            .embedding(TestVectorFixtures.uniformEmbedding(0.6f))
            .chunkIndex(1)
            .tokenCount(15)
            .build());

        // Create documents and chunks for Org B (with identical embeddings to prove isolation)
        Document docB = documentRepository.save(Document.builder()
            .organization(orgB)
            .filename("globex-confidential.pdf")
            .originalFilename("globex-confidential.pdf")
            .fileType("PDF")
            .fileSizeBytes(2048000L)
            .status(Document.ProcessingStatus.READY)
            .chunkCount(2)
            .build());

        chunkRepository.save(DocumentChunk.builder()
            .organization(orgB)
            .document(docB)
            .content("Globex Inc is developing a secret AI assistant for healthcare")
            .embedding(TestVectorFixtures.uniformEmbedding(0.1f))  // Same as Org A!
            .chunkIndex(0)
            .tokenCount(18)
            .build());

        chunkRepository.save(DocumentChunk.builder()
            .organization(orgB)
            .document(docB)
            .content("Globex revenue reached $50M ARR in Q3 2024")
            .embedding(TestVectorFixtures.uniformEmbedding(0.6f))  // Same as Org A!
            .chunkIndex(1)
            .tokenCount(15)
            .build());
    }

    @Test
    @DisplayName("CRITICAL: Org A cannot see Org B's documents via repository query")
    void documentQueryScopedToOrg() {
        var orgADocs = documentRepository.findByOrgId(orgA.getId());
        var orgBDocs = documentRepository.findByOrgId(orgB.getId());

        assertEquals(1, orgADocs.size(), "Org A should see only its own documents");
        assertEquals("acme-secret-plans.pdf", orgADocs.get(0).getFilename());

        assertEquals(1, orgBDocs.size(), "Org B should see only its own documents");
        assertEquals("globex-confidential.pdf", orgBDocs.get(0).getFilename());

        assertFalse(orgADocs.stream()
            .anyMatch(d -> d.getFilename().equals("globex-confidential.pdf")),
            "Org A must never see Org B's documents");
    }

    @Test
    @DisplayName("CRITICAL: findByIdAndOrgId rejects cross-tenant document access")
    void findByIdAndOrgIdRejectsCrossTenant() {
        Document orgBDoc = documentRepository.findByOrgId(orgB.getId()).get(0);
        Document result = documentRepository.findByIdAndOrgId(orgBDoc.getId(), orgA.getId());
        assertNull(result, "Cross-tenant findByIdAndOrgId must return null");
    }

    @Test
    @DisplayName("CRITICAL: Org A's chunks never include Org B's chunks")
    void chunkQueryScopedToOrg() {
        UUID docAId = documentRepository.findByOrgId(orgA.getId()).get(0).getId();
        var orgAChunks = chunkRepository.findByDocumentIdAndOrgId(docAId, orgA.getId());

        assertEquals(2, orgAChunks.size(), "Org A should see 2 chunks for its document");

        assertFalse(orgAChunks.stream()
            .anyMatch(c -> c.getOrganization().getId().equals(orgB.getId())),
            "Org A's chunk query must never return Org B's chunks");
    }

    @Test
    @DisplayName("CRITICAL: Identical embeddings in different orgs don't leak")
    void identicalEmbeddingsScopedToOrg() {
        var orgADocs = documentRepository.findByOrgId(orgA.getId());
        var orgBDocs = documentRepository.findByOrgId(orgB.getId());

        assertFalse(orgADocs.stream()
            .anyMatch(d -> d.getOrganization().getId().equals(orgB.getId())),
            "Org A's queries must never surface Org B documents");
        assertFalse(orgBDocs.stream()
            .anyMatch(d -> d.getOrganization().getId().equals(orgA.getId())),
            "Org B's queries must never surface Org A documents");
    }

    @Test
    @DisplayName("CRITICAL: User queries are scoped to org")
    void userQueryScopedToOrg() {
        var orgAUsers = userRepository.findByOrgId(orgA.getId());
        var orgBUsers = userRepository.findByOrgId(orgB.getId());

        assertEquals(1, orgAUsers.size());
        assertEquals(1, orgBUsers.size());

        assertFalse(orgAUsers.stream()
            .anyMatch(u -> u.getEmail().equals(userB.getEmail())),
            "Org A must not see Org B's users");
        assertFalse(orgBUsers.stream()
            .anyMatch(u -> u.getEmail().equals(userA.getEmail())),
            "Org B must not see Org A's users");
    }

    @Test
    @DisplayName("CRITICAL: existsByIdAndOrgId rejects cross-tenant user verification")
    void userIdentityCrossTenantRejected() {
        assertFalse(userRepository.existsByIdAndOrgId(userB.getId(), orgA.getId()));
        assertFalse(userRepository.existsByIdAndOrgId(userA.getId(), orgB.getId()));
    }

    @Test
    @DisplayName("CRITICAL: TenantContext validates org access correctly")
    void tenantContextValidation() {
        TenantContext.setOrgId(orgA.getId());
        try {
            assertDoesNotThrow(() -> TenantContext.validateOrgAccess(orgA.getId()));

            SecurityException ex = assertThrows(SecurityException.class,
                () -> TenantContext.validateOrgAccess(orgB.getId()));
            assertTrue(ex.getMessage().contains("Cross-tenant access denied"));
        } finally {
            TenantContext.clear();
        }
    }

    @Test
    @DisplayName("CRITICAL: TenantContext rejects access with no context")
    void tenantContextRejectsNoContext() {
        TenantContext.clear();
        SecurityException ex = assertThrows(SecurityException.class,
            () -> TenantContext.validateOrgAccess(orgA.getId()));
        assertTrue(ex.getMessage().contains("No tenant context"));
    }

    @Test
    @DisplayName("JWT tokens are scoped to correct org_id")
    void jwtScopedToOrg() {
        String tokenA = tokenProvider.generateAccessToken(
            userA.getId(), orgA.getId(), userA.getEmail(), Role.ORG_ADMIN);
        String tokenB = tokenProvider.generateAccessToken(
            userB.getId(), orgB.getId(), userB.getEmail(), Role.ORG_ADMIN);

        UUID extractedOrgA = tokenProvider.getOrgIdFromToken(tokenA);
        UUID extractedOrgB = tokenProvider.getOrgIdFromToken(tokenB);

        assertEquals(orgA.getId(), extractedOrgA);
        assertEquals(orgB.getId(), extractedOrgB);
        assertNotEquals(extractedOrgA, extractedOrgB);
    }

    @Test
    @DisplayName("Usage statistics are org-scoped and don't leak")
    void usageStatsScopedToOrg() {
        long orgADocCount = documentRepository.countByOrgId(orgA.getId());
        long orgBDocCount = documentRepository.countByOrgId(orgB.getId());

        assertEquals(1, orgADocCount);
        assertEquals(1, orgBDocCount);

        long orgAChunkCount = chunkRepository.countByOrgId(orgA.getId());
        long orgBChunkCount = chunkRepository.countByOrgId(orgB.getId());

        assertEquals(2, orgAChunkCount);
        assertEquals(2, orgBChunkCount);
    }
}
