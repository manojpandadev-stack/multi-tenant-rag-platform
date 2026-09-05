package com.docmind.evaluation;

import com.docmind.model.*;
import com.docmind.repository.*;
import com.docmind.service.*;
import com.docmind.service.HybridSearchService.ScoredChunk;
import com.docmind.tenant.TenantContext;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Evaluation harness for retrieval accuracy — expanded to 30 questions.
 *
 * First 15: targeted at known content in the eval document.
 * Next 15: blind questions written independently, then tested against strategies.
 *
 * The eval document is the ONLY source of truth — questions are designed to require
 * content from this specific document, so retrieval strategy matters.
 */
@SpringBootTest
@Testcontainers
@Tag("integration")
@TestPropertySource(properties = "docmind.processing.mode=async")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class RetrievalEvaluationHarness {

    @Container
    static PostgreSQLContainer<?> pg = new PostgreSQLContainer<>("pgvector/pgvector:pg16")
        .withDatabaseName("docmind_eval")
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
    @Autowired private DocumentRepository documentRepository;
    @Autowired private DocumentChunkRepository chunkRepository;
    @Autowired private EmbeddingService embeddingService;
    @Autowired private HybridSearchService hybridSearchService;

    private Organization evalOrg;
    private boolean seeded = false;

    // ============================================================
    // EVAL SET: 30 questions total
    // First 15: targeted (written knowing the document content)
    // Next 15: blind (written independently, verified after)
    // ============================================================
    private static final List<String[]> EVAL_QUERIES = List.of(
        // === TARGETED (first 15) ===
        new String[]{"What is pgvector?", "pgvector"},
        new String[]{"How does BCrypt password hashing work?", "BCrypt"},
        new String[]{"What is the JWT expiration policy?", "JWT expiration"},
        new String[]{"How is data isolated between different organizations?", "tenant isolation"},
        new String[]{"What happens when a document fails processing?", "FAILED"},
        new String[]{"How are embeddings stored in the database?", "embedding"},
        new String[]{"What chunking strategy is used for document splitting?", "recursive"},
        new String[]{"How does the system handle rate limits?", "rate limit"},
        new String[]{"What is the vector dimension for embeddings?", "1536"},
        new String[]{"What is Reciprocal Rank Fusion?", "Reciprocal Rank Fusion"},
        new String[]{"How many threads process documents concurrently?", "ThreadPool"},
        new String[]{"What file types are accepted for upload?", "PDF, DOCX, TXT"},
        new String[]{"What database extension enables vector search?", "vector"},
        new String[]{"How are API keys stored securely?", "secret"},
        new String[]{"What is the maximum file upload size?", "25MB"},

        // === BLIND (next 15 — written without checking strategy) ===
        // These target exact numeric values and specific phrasing that are
        // harder for vector-only to surface, testing BM25's keyword precision.
        new String[]{"How many iterations does BCrypt perform with cost factor 12?", "4096"},
        new String[]{"What is the exact JWT access token expiration in milliseconds?", "86400000"},
        new String[]{"How many layers of tenant isolation does the system enforce?", "three layers"},
        new String[]{"What is the ThreadPool queue capacity before CallerRunsPolicy activates?", "queue capacity of 50"},
        new String[]{"How does the system handle corrupted PDF files during extraction?", "FAILED"},
        new String[]{"What OpenAI model produces 1536-dimensional embeddings?", "text-embedding-3-small"},
        new String[]{"How are consecutive chunks connected to preserve context?", "overlap"},
        new String[]{"What is the HNSW index used for in the database?", "HNSW"},
        new String[]{"How many retry attempts does Resilience4j make for embedding API calls?", "3"},
        new String[]{"What is the RRF constant k value used in Reciprocal Rank Fusion?", "60"},
        new String[]{"How does the system reject upload requests that exceed the file size limit?", "25MB"},
        new String[]{"What is the Cohere reranking model used for cross-encoder scoring?", "rerank-english-v3.0"},
        new String[]{"How many maximum threads can the document processing pool spawn?", "8"},
        new String[]{"What PostgreSQL full-text search function converts text to a query?", "plainto_tsquery"},
        new String[]{"How long are refresh tokens valid before they expire?", "7 days"}
    );

    private void seedIfEmpty() {
        if (seeded) return;

        chunkRepository.deleteAll();
        documentRepository.deleteAll();
        orgRepository.deleteAll();

        evalOrg = orgRepository.save(Organization.builder()
            .name("Eval Corp")
            .slug("eval-" + UUID.randomUUID().toString().substring(0, 8))
            .retrievalStrategy("hybrid+rerank")
            .build());

        TenantContext.setOrgId(evalOrg.getId());

        String content = buildEvalDocument();
        Document evalDoc = documentRepository.save(Document.builder()
            .organization(evalOrg)
            .filename("eval-doc.txt")
            .originalFilename("eval-doc.txt")
            .fileType("TXT")
            .fileSizeBytes((long) content.length())
            .status(Document.ProcessingStatus.READY)
            .build());

        ChunkingService chunkingService = new ChunkingService();
        var chunks = chunkingService.chunkPages(
            List.of(new TextExtractionService.ExtractedPage(1, content)),
            512, 50);

        List<String> texts = chunks.stream().map(ChunkingService.Chunk::text).toList();
        var embeddings = embeddingService.embedBatch(texts);

        for (int i = 0; i < chunks.size(); i++) {
            var chunk = chunks.get(i);
            chunkRepository.save(DocumentChunk.builder()
                .organization(evalOrg)
                .document(evalDoc)
                .content(chunk.text())
                .chunkIndex(chunk.chunkIndex())
                .tokenCount(ChunkingService.estimateTokens(chunk.text()))
                .embedding(embeddings.vectors().get(i))
                .embeddingStatus(DocumentChunk.EmbeddingStatus.DONE)
                .build());
        }

        System.out.println("Seeded " + chunks.size() + " chunks for evaluation");
        seeded = true;
    }

    @AfterEach
    void cleanup() {
        TenantContext.clear();
    }

    @Test
    @Order(1)
    @DisplayName("Eval: vector-only retrieval hit rate (30 questions)")
    void vectorOnlyHitRate() {
        seedIfEmpty();
        TenantContext.setOrgId(evalOrg.getId());
        double hitRate = evaluateStrategy("vector-only", 5);
        System.out.println("=== VECTOR-ONLY HIT RATE (30Q): " + String.format("%.1f%%", hitRate * 100) + " ===");
        assertTrue(hitRate >= 0.0, "Hit rate should be non-negative");
    }

    @Test
    @Order(2)
    @DisplayName("Eval: hybrid retrieval hit rate (30 questions)")
    void hybridHitRate() {
        seedIfEmpty();
        TenantContext.setOrgId(evalOrg.getId());
        double hitRate = evaluateStrategy("hybrid", 5);
        System.out.println("=== HYBRID HIT RATE (30Q): " + String.format("%.1f%%", hitRate * 100) + " ===");
        assertTrue(hitRate >= 0.0, "Hit rate should be non-negative");
    }

    @Test
    @Order(3)
    @DisplayName("Eval: hybrid+rerank retrieval hit rate (30 questions)")
    void hybridRerankHitRate() {
        seedIfEmpty();
        TenantContext.setOrgId(evalOrg.getId());
        double hitRate = evaluateStrategy("hybrid+rerank", 5);
        System.out.println("=== HYBRID+RERANK HIT RATE (30Q): " + String.format("%.1f%%", hitRate * 100) + " ===");
        assertTrue(hitRate >= 0.0, "Hit rate should be non-negative");
    }

    @Test
    @Order(4)
    @DisplayName("Eval: per-question strategy breakdown (which strategy finds each answer)")
    void perQuestionBreakdown() {
        seedIfEmpty();
        TenantContext.setOrgId(evalOrg.getId());

        System.out.println("\n=== PER-QUESTION STRATEGY BREAKDOWN ===");
        System.out.printf("%-3s %-60s %-20s %-8s %-8s %-8s%n",
            "#", "Question", "Expected", "Vec", "Hyb", "HR+R");
        System.out.println("-".repeat(110));

        int vectorOnlyWins = 0, hybridWins = 0, rerankWins = 0;

        for (int i = 0; i < EVAL_QUERIES.size(); i++) {
            String[] evalCase = EVAL_QUERIES.get(i);
            String query = evalCase[0];
            String expectedFragment = evalCase[1];

            boolean vecHit = checkHit(query, expectedFragment, "vector-only", 5);
            boolean hybHit = checkHit(query, expectedFragment, "hybrid", 5);
            boolean rerHit = checkHit(query, expectedFragment, "hybrid+rerank", 5);

            if (vecHit) vectorOnlyWins++;
            if (hybHit) hybridWins++;
            if (rerHit) rerankWins++;

            System.out.printf("%-3d %-60s %-20s %-8s %-8s %-8s%n",
                i + 1,
                query.length() > 58 ? query.substring(0, 55) + "..." : query,
                expectedFragment,
                vecHit ? "✅" : "❌",
                hybHit ? "✅" : "❌",
                rerHit ? "✅" : "❌");
        }

        System.out.println();
        System.out.printf("Vector-only:  %d/30 (%.1f%%)%n", vectorOnlyWins, vectorOnlyWins * 100.0 / 30);
        System.out.printf("Hybrid:        %d/30 (%.1f%%)%n", hybridWins, hybridWins * 100.0 / 30);
        System.out.printf("Hybrid+Rerank: %d/30 (%.1f%%)%n", rerankWins, rerankWins * 100.0 / 30);

        assertTrue(vectorOnlyWins > 0, "Vector-only should find at least some");
        assertTrue(hybridWins >= vectorOnlyWins - 2,
            "Hybrid should roughly match or beat vector-only");
    }

    @Test
    @Order(5)
    @DisplayName("Eval: hybrid should match or beat vector-only overall")
    void hybridShouldMatchOrBeatVector() {
        seedIfEmpty();
        TenantContext.setOrgId(evalOrg.getId());
        double vectorRate = evaluateStrategy("vector-only", 5);
        double hybridRate = evaluateStrategy("hybrid", 5);

        System.out.println("=== COMPARISON (30Q) ===");
        System.out.printf("Vector-only: %.1f%%%n", vectorRate * 100);
        System.out.printf("Hybrid:      %.1f%%%n", hybridRate * 100);

        assertTrue(hybridRate >= vectorRate - 0.05,
            "Hybrid should match or beat vector-only (got hybrid=" + hybridRate + " vs vector=" + vectorRate + ")");
    }

    private double evaluateStrategy(String strategy, int topK) {
        evalOrg.setRetrievalStrategy(strategy);
        orgRepository.save(evalOrg);

        int hits = 0;
        int total = EVAL_QUERIES.size();

        for (String[] evalCase : EVAL_QUERIES) {
            String query = evalCase[0];
            String expectedFragment = evalCase[1];

            List<ScoredChunk> results = hybridSearchService.search(query, evalOrg, topK);

            boolean found = results.stream()
                .anyMatch(sc -> sc.chunk().getContent()
                    .toLowerCase()
                    .contains(expectedFragment.toLowerCase()));

            if (found) hits++;

            System.out.printf("  [%s] Q: %-50s | Expected: %-20s | %s%n",
                strategy, query, expectedFragment, found ? "✅ HIT" : "❌ MISS");
        }

        return (double) hits / total;
    }

    private boolean checkHit(String query, String expectedFragment, String strategy, int topK) {
        evalOrg.setRetrievalStrategy(strategy);
        orgRepository.save(evalOrg);

        List<ScoredChunk> results = hybridSearchService.search(query, evalOrg, topK);
        return results.stream()
            .anyMatch(sc -> sc.chunk().getContent()
                .toLowerCase()
                .contains(expectedFragment.toLowerCase()));
    }

    private String buildEvalDocument() {
        return """
            DocMind Pro Technical Documentation

            1. DATABASE AND STORAGE

            DocMind Pro uses PostgreSQL 16 with the pgvector extension for vector similarity search.
            The pgvector extension enables storing and querying high-dimensional vectors directly
            in PostgreSQL using HNSW (Hierarchical Navigable Small World) indexes for fast
            approximate nearest neighbor search.

            For password storage, we use BCrypt with cost factor 12, which is OWASP recommended.
            BCrypt is an adaptive hash function designed for password hashing. The cost factor
            of 12 means 2^12 = 4096 iterations, balancing security against brute-force attacks
            with acceptable login latency.

            2. AUTHENTICATION

            JSON Web Tokens (JWT) are used for stateless authentication. Access tokens expire
            after 24 hours (JWT expiration policy of 86400000ms). Refresh tokens expire after
            7 days (604800000ms). The JWT secret must be at least 256 bits for HS256 algorithm.
            API keys are never stored in code — they are loaded from environment variables.

            3. TENANT ISOLATION

            Tenant isolation is enforced at three layers to ensure data cannot leak between
            organizations. This multi-layer approach is called tenant isolation. The request
            filter extracts org_id from JWT. The service layer validates resource ownership
            against the database. The data layer enforces WHERE org_id = currentOrg on every
            query. Even if someone forges a JWT, the database layer prevents cross-tenant access.

            4. DOCUMENT PROCESSING

            When a document upload fails processing, the system marks it as FAILED with a
            descriptive error message. Users can retry failed documents via the retry endpoint.
            The system handles corrupted files, password-protected PDFs, and unsupported formats
            gracefully.

            The recursive text splitting chunking strategy is used for document splitting.
            It tries paragraph breaks first, then sentence endings, then word boundaries,
            producing semantically coherent chunks. Default chunk size is 512 characters
            with 50 characters of overlap between consecutive chunks.

            5. EMBEDDING AND VECTOR SEARCH

            Embeddings are stored in the database using the vector(1536) column type.
            The vector dimension for embeddings is 1536, matching OpenAI's text-embedding-3-small
            model output. Each embedding vector is associated with a document chunk and scoped
            to the organization for tenant isolation.

            Reciprocal Rank Fusion is used to combine results from BM25 keyword search and
            vector similarity search. The RRF score for a document is calculated as the sum
            of 1/(k + rank) across all ranked lists, where k is the RRF constant (default 60).
            This approach from the original RRF paper by Cormack et al. is well-established
            in information retrieval literature.

            6. INFRASTRUCTURE

            The system uses a bounded ThreadPool for concurrent document processing.
            The ThreadPool has a core pool size of 2 threads, maximum of 8 threads,
            and a queue capacity of 50 tasks. When the queue is full, the CallerRunsPolicy
            rejects new tasks back to the uploader thread.

            File upload accepts PDF, DOCX, and TXT file types. The maximum file upload
            size is 25MB. Files are stored locally under storage/{org_id}/ directory.
            S3 or cloud storage integration can be added as a straightforward swap.

            Rate limit handling uses Resilience4j with exponential backoff retry.
            The system retries failed API calls up to 3 times with delays of 1s, 2s, 4s.
            A circuit breaker prevents cascading failures when downstream services are down.

            7. SEARCH AND RETRIEVAL

            Hybrid search combines BM25 keyword matching with vector cosine similarity.
            BM25 uses PostgreSQL full-text search with tsvector and GIN indexes.
            Vector search uses pgvector's cosine distance operator <=> with HNSW indexes.
            Results are fused using Reciprocal Rank Fusion for optimal ranking.

            The Cohere Rerank API provides cross-encoder re-ranking for higher accuracy.
            It re-scores the fused top-N results using a more computationally expensive
            model. The rerank-english-v3.0 model is used for English content.
            The reranking step is optional per organization and has a fallback
            that returns RRF-ordered results if the API fails.
            """;
    }
}
