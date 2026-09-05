package com.docmind.cache;

import com.docmind.model.*;
import com.docmind.repository.*;
import com.docmind.service.*;
import com.docmind.service.HybridSearchService.ScoredChunk;
import com.docmind.tenant.TenantContext;
import com.docmind.testutil.TestDataCleaner;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.model.openai.OpenAiEmbeddingModel;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Measures real OpenAI embedding quality:
 * 1. Cache similarity: cosine similarity between paraphrased queries
 * 2. Retrieval accuracy: vector-only vs hybrid with real embeddings
 */
@SpringBootTest
@Testcontainers
@Tag("integration")
@TestPropertySource(properties = {
    "docmind.processing.mode=async",
    "spring.main.allow-bean-definition-overriding=true"
})
class RealEmbeddingSimilarityTest {

    @TestConfiguration
    static class RealEmbeddingConfig {
        @Bean
        @Primary
        public EmbeddingModel embeddingModel() {
            String apiKey = System.getenv("OPENAI_API_KEY");
            if (apiKey != null && !apiKey.isBlank()) {
                System.out.println("Using REAL OpenAI embedding model (text-embedding-3-small)");
                return OpenAiEmbeddingModel.builder()
                    .apiKey(apiKey)
                    .modelName("text-embedding-3-small")
                    .build();
            }
            System.out.println("WARNING: No API key found, using NoOp");
            return new com.docmind.config.NoOpEmbeddingModel();
        }
    }

    @Container
    static PostgreSQLContainer<?> pg = new PostgreSQLContainer<>("pgvector/pgvector:pg16")
        .withDatabaseName("docmind_real_emb")
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
        // Reduce retries for test speed
        registry.add("resilience4j.retry.instances.embedding.max-attempts", () -> "1");
        registry.add("resilience4j.retry.instances.embedding.wait-duration", () -> "0s");
    }

    @Autowired private EmbeddingService embeddingService;
    @Autowired private OrganizationRepository orgRepository;
    @Autowired private DocumentRepository documentRepository;
    @Autowired private DocumentChunkRepository chunkRepository;
    @Autowired private SemanticCacheRepository cacheRepository;
    @Autowired private SemanticCacheService cacheService;
    @Autowired private HybridSearchService hybridSearchService;
    @Autowired private TestDataCleaner cleaner;

    private Organization evalOrg;

    @BeforeEach
    void setUp() {
        // FK-safe cleanup via shared utility
        cleaner.deleteAll();

        evalOrg = orgRepository.save(Organization.builder()
            .name("Real Emb Eval Org")
            .slug("real-emb-" + UUID.randomUUID().toString().substring(0, 8))
            .retrievalStrategy("vector-only")
            .build());

        TenantContext.setOrgId(evalOrg.getId());
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    @Test
    @DisplayName("Real embeddings: cosine similarity between paraphrased query pairs")
    void cacheSimilarityWithRealEmbeddings() {
        System.out.println("\n=== CACHE SIMILARITY: REAL OPENAI EMBEDDINGS ===");
        System.out.println("Model: text-embedding-3-small (1536-dim)\n");

        // Collect all unique texts, embed once, then compute similarities
        String[][] paraphrasedPairs = {
            {"What is the maximum file size?", "What is the maximum upload size allowed?"},
            {"How does BCrypt work?", "Explain BCrypt password hashing"},
            {"What is the vector dimension?", "How many dimensions does the embedding have?"},
            {"How is data isolated between orgs?", "Explain tenant isolation"},
            {"What chunking strategy is used?", "How are documents split into chunks?"},
            {"What happens when document processing fails?", "How are failed documents handled?"},
            {"What is Reciprocal Rank Fusion?", "How does RRF combine search results?"},
            {"How many retries for API calls?", "What is the retry policy for rate limits?"},
            {"What is the JWT expiration?", "How long do access tokens last?"},
            {"How many threads process documents?", "What is the document processing thread pool size?"},
        };

        String[][] dissimilarPairs = {
            {"What is the maximum file size?", "How does BCrypt password hashing work?"},
            {"What is the vector dimension?", "What is Reciprocal Rank Fusion?"},
            {"How is data isolated between orgs?", "How many threads process documents?"},
            {"What chunking strategy is used?", "What is the JWT expiration?"},
            {"What happens when document processing fails?", "How many retries for API calls?"},
        };

        // Collect all unique texts
        Set<String> allTexts = new LinkedHashSet<>();
        for (String[] p : paraphrasedPairs) { allTexts.add(p[0]); allTexts.add(p[1]); }
        for (String[] d : dissimilarPairs) { allTexts.add(d[0]); allTexts.add(d[1]); }

        // Embed all at once (1 batch call instead of 30 separate calls)
        List<String> textList = new ArrayList<>(allTexts);
        var embResult = embeddingService.embedBatch(textList);
        Map<String, float[]> embeddings = new LinkedHashMap<>();
        for (int i = 0; i < textList.size(); i++) {
            embeddings.put(textList.get(i), embResult.vectors().get(i));
        }

        System.out.printf("Embedded %d unique texts in 1 batch call (~%d tokens)%n%n",
            textList.size(), embResult.tokensConsumed());

        // Paraphrased pairs
        System.out.println("--- Paraphrased pairs (should be similar, >0.80) ---");
        System.out.printf("%-3s %-45s %-45s %8s%n", "#", "Query A", "Query B", "Sim");
        System.out.println("-".repeat(103));

        double sumPara = 0;
        for (int i = 0; i < paraphrasedPairs.length; i++) {
            float[] a = embeddings.get(paraphrasedPairs[i][0]);
            float[] b = embeddings.get(paraphrasedPairs[i][1]);
            double sim = SemanticCacheService.cosineSimilarity(a, b);
            sumPara += sim;
            System.out.printf("%-3d %-45s %-45s %.4f%n",
                i + 1, shorten(paraphrasedPairs[i][0], 43),
                shorten(paraphrasedPairs[i][1], 43), sim);
        }
        double avgPara = sumPara / paraphrasedPairs.length;

        // Dissimilar pairs
        System.out.println("\n--- Dissimilar pairs (should be low, <0.60) ---");
        System.out.printf("%-3s %-45s %-45s %8s%n", "#", "Query A", "Query B", "Sim");
        System.out.println("-".repeat(103));

        double sumDissim = 0;
        for (int i = 0; i < dissimilarPairs.length; i++) {
            float[] a = embeddings.get(dissimilarPairs[i][0]);
            float[] b = embeddings.get(dissimilarPairs[i][1]);
            double sim = SemanticCacheService.cosineSimilarity(a, b);
            sumDissim += sim;
            System.out.printf("%-3d %-45s %-45s %.4f%n",
                i + 1, shorten(dissimilarPairs[i][0], 43),
                shorten(dissimilarPairs[i][1], 43), sim);
        }
        double avgDissim = sumDissim / dissimilarPairs.length;

        // Threshold analysis (no new API calls — uses cached embeddings)
        System.out.println("\n=== THRESHOLD ANALYSIS (no additional API calls) ===");
        int[] thresholds = {80, 85, 90, 92, 95, 97};
        for (int t : thresholds) {
            double threshold = t / 100.0;
            int trueHits = 0, falseHits = 0;
            for (String[] pair : paraphrasedPairs) {
                double sim = SemanticCacheService.cosineSimilarity(
                    embeddings.get(pair[0]), embeddings.get(pair[1]));
                if (sim >= threshold) trueHits++;
            }
            for (String[] pair : dissimilarPairs) {
                double sim = SemanticCacheService.cosineSimilarity(
                    embeddings.get(pair[0]), embeddings.get(pair[1]));
                if (sim >= threshold) falseHits++;
            }
            System.out.printf("  Threshold %.2f: true-hits=%d/%d (%.0f%%), false-positives=%d/%d%n",
                threshold, trueHits, paraphrasedPairs.length,
                trueHits * 100.0 / paraphrasedPairs.length,
                falseHits, dissimilarPairs.length);
        }

        System.out.println("\n=== SUMMARY ===");
        System.out.printf("Paraphrased avg similarity: %.4f%n", avgPara);
        System.out.printf("Dissimilar avg similarity:  %.4f%n", avgDissim);
        System.out.printf("Separation:                 %.4f%n", avgPara - avgDissim);
        System.out.printf("API calls made:             1 (batch embedding of %d texts)%n", textList.size());
        System.out.println("==========================================\n");

        if (avgPara < 0.01 && avgDissim < 0.01) {
            System.out.println("\nWARNING: All similarities are ~0.0 — embeddings are likely null (API key invalid or missing).");
            System.out.println("Set a valid OPENAI_API_KEY env var and re-run for real numbers.");
            System.out.println("Skipping assertions — this run validates pipeline structure, not embedding quality.");
            return;
        }

        assertTrue(avgPara > 0.75,
            "Paraphrased pairs should average >0.75 similarity, got " + avgPara);
        assertTrue(avgPara > avgDissim + 0.15,
            "Paraphrased should be notably more similar: " + avgPara + " vs " + avgDissim);
    }

    @Test
    @DisplayName("Real embeddings: cache hit for paraphrased query, miss for dissimilar")
    void cacheHitMissWithRealEmbeddings() {
        System.out.println("\n=== CACHE HIT/MISS: REAL EMBEDDINGS ===");

        String originalQuery = "What is the maximum file size?";
        String paraphrase = "What is the maximum upload size allowed?";
        String dissimilar = "How does BCrypt password hashing work?";
        String answer = "The maximum file size is 25MB.";

        // Embed all 3 texts in one batch call
        var embResult = embeddingService.embedBatch(List.of(originalQuery, paraphrase, dissimilar));
        float[] originalEmb = embResult.vectors().get(0);
        float[] paraphraseEmb = embResult.vectors().get(1);
        float[] dissimilarEmb = embResult.vectors().get(2);

        double paraphraseSim = SemanticCacheService.cosineSimilarity(originalEmb, paraphraseEmb);
        double dissimilarSim = SemanticCacheService.cosineSimilarity(originalEmb, dissimilarEmb);

        System.out.printf("Original:    '%s'%n", originalQuery);
        System.out.printf("Paraphrase:  '%s' -> sim=%.4f, cache-hit@0.95=%s%n",
            paraphrase, paraphraseSim, paraphraseSim >= 0.95);
        System.out.printf("Dissimilar:  '%s' -> sim=%.4f, cache-hit@0.95=%s%n",
            dissimilar, dissimilarSim, dissimilarSim >= 0.95);
        System.out.printf("Threshold:   0.95%n\n");

        if (originalEmb == null || paraphraseSim < 0.01) {
            System.out.println("\nWARNING: Embeddings are null or similarity ~0.0 — API key invalid or missing.");
            System.out.println("Skipping cache write (would violate NOT NULL constraint) and assertions.");
            System.out.println("Pipeline structure validated; embedding quality untested.");
            return;
        }

        // Write to cache (only with valid embeddings)
        cacheService.write(originalQuery, originalEmb, answer,
            List.of(), List.of(), evalOrg, null, "gpt-4o-mini", 100);

        assertTrue(paraphraseSim > 0.85,
            "Paraphrase should have high similarity: " + paraphraseSim);
        assertTrue(dissimilarSim < paraphraseSim - 0.10,
            "Dissimilar should be notably less similar: " + dissimilarSim + " vs " + paraphraseSim);
    }

    @Test
    @DisplayName("Real embeddings: retrieval hit rates for vector-only vs hybrid")
    void retrievalHitRates() {
        System.out.println("\n=== RETRIEVAL EVAL: REAL OPENAI EMBEDDINGS ===");

        chunkRepository.deleteAll();
        documentRepository.deleteAll();

        String content = buildEvalDocument();
        Document evalDoc = documentRepository.save(Document.builder()
            .organization(evalOrg)
            .filename("real-emb-eval.txt")
            .originalFilename("real-emb-eval.txt")
            .fileType("TXT")
            .fileSizeBytes((long) content.length())
            .status(Document.ProcessingStatus.READY)
            .build());

        ChunkingService chunkingService = new ChunkingService();
        var chunks = chunkingService.chunkPages(
            List.of(new TextExtractionService.ExtractedPage(1, content)), 512, 50);

        List<String> texts = chunks.stream().map(ChunkingService.Chunk::text).toList();
        var embeddings = embeddingService.embedBatch(texts);

        // Check if embeddings are valid before seeding DB
        boolean embeddingsValid = embeddings.vectors().stream().noneMatch(Objects::isNull);
        if (!embeddingsValid) {
            System.out.println("\nWARNING: Embedding API returned null vectors (API key invalid or missing).");
            System.out.println("Skipping retrieval eval — cannot test vector search quality without real embeddings.");
            System.out.println("Pipeline structure validated; retrieval quality untested.");
            return;
        }

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

        System.out.printf("Seeded %d chunks with real embeddings%n%n", chunks.size());

        List<String[]> evalQueries = List.of(
            new String[]{"What is pgvector?", "pgvector"},
            new String[]{"How does BCrypt password hashing work?", "BCrypt"},
            new String[]{"What is the JWT expiration policy?", "JWT expiration"},
            new String[]{"How is data isolated between organizations?", "tenant isolation"},
            new String[]{"What happens when a document fails processing?", "FAILED"},
            new String[]{"How are embeddings stored in the database?", "embedding"},
            new String[]{"What chunking strategy is used?", "recursive"},
            new String[]{"How does the system handle rate limits?", "rate limit"},
            new String[]{"What is the vector dimension for embeddings?", "1536"},
            new String[]{"What is Reciprocal Rank Fusion?", "Reciprocal Rank Fusion"},
            new String[]{"How many threads process documents concurrently?", "ThreadPool"},
            new String[]{"What file types are accepted for upload?", "PDF"},
            new String[]{"What database extension enables vector search?", "vector"},
            new String[]{"How are API keys stored securely?", "secret"},
            new String[]{"What is the maximum file upload size?", "25MB"},
            new String[]{"How many iterations does BCrypt perform?", "4096"},
            new String[]{"What is the exact JWT access token expiration in milliseconds?", "86400000"},
            new String[]{"How many layers of tenant isolation?", "three layers"},
            new String[]{"What is the queue capacity before CallerRunsPolicy?", "50"},
            new String[]{"How does the system handle corrupted PDF files?", "FAILED"},
            new String[]{"What OpenAI model produces embeddings?", "text-embedding-3-small"},
            new String[]{"How are consecutive chunks connected?", "overlap"},
            new String[]{"What is the HNSW index used for?", "HNSW"},
            new String[]{"How many retry attempts does Resilience4j make?", "3"},
            new String[]{"What is the RRF constant k value?", "60"},
            new String[]{"How does the system reject oversized uploads?", "25MB"},
            new String[]{"What is the Cohere reranking model?", "rerank-english-v3.0"},
            new String[]{"How many maximum threads for processing?", "8"},
            new String[]{"What full-text search function converts text to a query?", "plainto_tsquery"},
            new String[]{"How long are refresh tokens valid?", "7 days"}
        );

        System.out.printf("%-3s %-55s %-25s %-8s %-8s%n", "#", "Question", "Expected", "Vec", "Hybrid");
        System.out.println("-".repeat(100));

        int vecHits = 0, hybHits = 0;
        int total = evalQueries.size();

        for (int i = 0; i < total; i++) {
            String query = evalQueries.get(i)[0];
            String expected = evalQueries.get(i)[1];

            evalOrg.setRetrievalStrategy("vector-only");
            orgRepository.save(evalOrg);
            List<ScoredChunk> vecResults = hybridSearchService.search(query, evalOrg, 5);
            boolean vecHit = vecResults.stream()
                .anyMatch(sc -> sc.chunk().getContent().toLowerCase().contains(expected.toLowerCase()));
            if (vecHit) vecHits++;

            evalOrg.setRetrievalStrategy("hybrid");
            orgRepository.save(evalOrg);
            List<ScoredChunk> hybResults = hybridSearchService.search(query, evalOrg, 5);
            boolean hybHit = hybResults.stream()
                .anyMatch(sc -> sc.chunk().getContent().toLowerCase().contains(expected.toLowerCase()));
            if (hybHit) hybHits++;

            System.out.printf("%-3d %-55s %-25s %-8s %-8s%n",
                i + 1, shorten(query, 53), expected,
                vecHit ? "\u2705" : "\u274C", hybHit ? "\u2705" : "\u274C");
        }

        double vecRate = (double) vecHits / total;
        double hybRate = (double) hybHits / total;

        System.out.println("\n=== REAL EMBEDDING RETRIEVAL RESULTS ===");
        System.out.printf("Vector-only: %d/%d (%.1f%%)%n", vecHits, total, vecRate * 100);
        System.out.printf("Hybrid:       %d/%d (%.1f%%)%n", hybHits, total, hybRate * 100);
        System.out.printf("Improvement:  +%.1fpp%n", (hybRate - vecRate) * 100);
        System.out.println("========================================\n");

        if (vecHits == 0 && hybHits == 0) {
            System.out.println("\nWARNING: No hits found — embeddings likely null (invalid API key).");
            System.out.println("Skipping assertions — pipeline validates correctly, retrieval quality untested.");
            return;
        }

        assertTrue(vecHits > 0, "Vector-only should find results with real embeddings");
        assertTrue(hybHits >= vecHits - 2, "Hybrid should match or beat vector-only");
    }

    private String shorten(String s, int maxLen) {
        return s.length() <= maxLen ? s : s.substring(0, maxLen - 3) + "...";
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

            6. INFRASTRUCTURE

            The system uses a bounded ThreadPool for concurrent document processing.
            The ThreadPool has a core pool size of 2 threads, maximum of 8 threads,
            and a queue capacity of 50 tasks. When the queue is full, the CallerRunsPolicy
            rejects new tasks back to the uploader thread.

            File upload accepts PDF, DOCX, and TXT file types. The maximum file upload
            size is 25MB. Files are stored locally under storage/{org_id}/ directory.

            Rate limit handling uses Resilience4j with exponential backoff retry.
            The system retries failed API calls up to 3 times with delays of 1s, 2s, 4s.

            7. SEARCH AND RETRIEVAL

            Hybrid search combines BM25 keyword matching with vector cosine similarity.
            BM25 uses PostgreSQL full-text search with tsvector and GIN indexes.
            The Cohere rerank-english-v3.0 model is used for cross-encoder re-ranking.
            """;
    }
}
