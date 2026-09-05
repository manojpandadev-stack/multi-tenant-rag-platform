package com.docmind.service;

import com.docmind.model.UsagePeriodSummary;
import com.docmind.repository.UsagePeriodSummaryRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.sql.DataSource;
import java.sql.*;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.UUID;

/**
 * Records usage events atomically using UPDATE ... SET count = count + 1
 * to prevent lost updates under concurrent requests from the same org.
 *
 * Design decision: SYNCHRONOUS recording in the request path.
 * Why synchronous:
 * - Accuracy: every event is counted, no fire-and-forget gaps
 * - Simplicity: no message queue or async retry needed
 * - Latency impact: UPDATE on a small row is <5ms (one SQL round-trip)
 * - The alternative (async/batch) risks undercounting on failures,
 *   which is unacceptable for billing accuracy
 *
 * At scale (millions of queries/day), you would:
 * - Use Kafka + a consumer that batches updates (reduces DB writes)
 * - Or use Redis INCRBY for hot counters, synced to Postgres periodically
 *
 * The UPDATE is a single SQL statement — no read-modify-write cycle,
 * so concurrent requests from the same org cannot lose updates.
 *
 * Period creation uses raw JDBC (DataSource.getConnection()) to completely
 * bypass Spring's transaction manager. Spring Data and JdbcTemplate both
 * participate in the current transaction, which can be set to readOnly by
 * various Spring components (Hibernate, repositories, etc.). Using a raw
 * connection guarantees the INSERT always executes as read-write.
 * The period INSERT and the counter UPDATE don't need to be in the same
 * transaction — ON CONFLICT DO NOTHING is idempotent, and the atomic
 * UPDATE handles the actual counting.
 */
@Service
public class UsageRecordingService {

    private static final Logger log = LoggerFactory.getLogger(UsageRecordingService.class);

    private final UsagePeriodSummaryRepository usageRepository;
    private final DataSource dataSource;

    public UsageRecordingService(UsagePeriodSummaryRepository usageRepository, DataSource dataSource) {
        this.usageRepository = usageRepository;
        this.dataSource = dataSource;
    }

    // ============================================================
    // Public recording methods — called from service layer
    // ============================================================

    public void recordDocumentUpload(UUID orgId, long fileSizeBytes) {
        UUID periodId = getOrCreateCurrentPeriod(orgId);
        usageRepository.incrementCounters(periodId, 1, fileSizeBytes,
            0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0);
        log.debug("Recorded document upload: org={}, size={}bytes", orgId, fileSizeBytes);
    }

    public void recordEmbeddingTokens(UUID orgId, int tokens) {
        UUID periodId = getOrCreateCurrentPeriod(orgId);
        double costCents = tokens * 0.02 / 1000.0;
        usageRepository.incrementCounters(periodId, 0, 0,
            0, 0, 0, 0, tokens, 0, 0, 0, 0, 0, (long) Math.round(costCents * 1000));
        log.debug("Recorded embedding tokens: org={}, tokens={}", orgId, tokens);
    }

    public void recordQuery(UUID orgId, String retrievalStrategy,
                            int embeddingTokens, int llmInputTokens, int llmOutputTokens,
                            boolean rerankCalled, double costCents) {
        UUID periodId = getOrCreateCurrentPeriod(orgId);
        int vecOnly = "vector-only".equals(retrievalStrategy) ? 1 : 0;
        int rerank = ("hybrid+rerank".equals(retrievalStrategy) || "HYBRID_RERANK".equals(retrievalStrategy)) ? 1 : 0;
        int hybrid = (vecOnly == 0 && rerank == 0) ? 1 : 0;

        usageRepository.incrementCounters(periodId, 0, 0,
            1, vecOnly, hybrid, rerank, embeddingTokens, llmInputTokens, llmOutputTokens,
            0, 1, rerankCalled ? 1 : 0, (long) Math.round(costCents * 1000));
        log.debug("Recorded query (cache miss): org={}, strategy={}", orgId, retrievalStrategy);
    }

    public void recordCacheHit(UUID orgId, String retrievalStrategy) {
        UUID periodId = getOrCreateCurrentPeriod(orgId);
        int vecOnly = "vector-only".equals(retrievalStrategy) ? 1 : 0;
        int rerank = ("hybrid+rerank".equals(retrievalStrategy) || "HYBRID_RERANK".equals(retrievalStrategy)) ? 1 : 0;
        int hybrid = (vecOnly == 0 && rerank == 0) ? 1 : 0;

        usageRepository.incrementCounters(periodId, 0, 0,
            1, vecOnly, hybrid, rerank, 0, 0, 0, 1, 0, 0, 0);
        log.debug("Recorded cache hit: org={}, strategy={}", orgId, retrievalStrategy);
    }

    // ============================================================
    // Period management — raw JDBC, outside Spring's tx manager
    // ============================================================

    public UUID getOrCreateCurrentPeriod(UUID orgId) {
        String currentPeriod = currentBillingPeriod();
        return getOrCreatePeriod(orgId, currentPeriod);
    }

    /**
     * Atomically insert a usage period if not exists, then return its ID.
     * Uses raw JDBC (DataSource.getConnection()) to completely bypass Spring's
     * transaction manager, which can set readOnly on the connection via
     * Hibernate, Spring Data repositories, or JdbcTemplate participation.
     *
     * This is safe for concurrent access:
     * - ON CONFLICT DO NOTHING handles the race between two threads
     *   trying to INSERT the same row simultaneously
     * - PostgreSQL blocks the second INSERT until the first commits,
     *   then the second proceeds (doing nothing), so both threads
     *   see the same row afterward
     * - The subsequent incrementCounters UPDATE is atomic SQL
     */
    public UUID getOrCreatePeriod(UUID orgId, String billingPeriod) {
        String insertSql = """
            INSERT INTO usage_periods (id, org_id, billing_period, documents_uploaded, storage_bytes,
                queries_total, queries_vector_only, queries_hybrid, queries_hybrid_rerank,
                embedding_tokens, llm_input_tokens, llm_output_tokens,
                cache_hits, cache_misses, rerank_calls, estimated_cost_cents,
                created_at, updated_at)
            VALUES (gen_random_uuid(), ?::uuid, ?, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, NOW(), NOW())
            ON CONFLICT (org_id, billing_period) DO NOTHING
            """;

        String selectSql = """
            SELECT id FROM usage_periods WHERE org_id = ?::uuid AND billing_period = ?
            """;

        // Raw JDBC — completely outside Spring's transaction manager
        try (Connection conn = dataSource.getConnection()) {
            // Auto-commit so the INSERT is immediately visible to other connections
            boolean origAutoCommit = conn.getAutoCommit();
            try {
                conn.setAutoCommit(true);

                try (PreparedStatement insertPs = conn.prepareStatement(insertSql)) {
                    insertPs.setString(1, orgId.toString());
                    insertPs.setString(2, billingPeriod);
                    insertPs.executeUpdate();
                }

                UUID periodId;
                try (PreparedStatement selectPs = conn.prepareStatement(selectSql)) {
                    selectPs.setString(1, orgId.toString());
                    selectPs.setString(2, billingPeriod);
                    try (ResultSet rs = selectPs.executeQuery()) {
                        if (!rs.next()) {
                            throw new RuntimeException("Failed to find or create usage period for org=" + orgId);
                        }
                        periodId = rs.getObject("id", UUID.class);
                    }
                }

                return periodId;
            } finally {
                conn.setAutoCommit(origAutoCommit);
            }
        } catch (SQLException e) {
            throw new RuntimeException("Failed to get or create usage period", e);
        }
    }

    public static String currentBillingPeriod() {
        return DateTimeFormatter.ofPattern("yyyy-MM")
            .withZone(ZoneOffset.UTC).format(Instant.now());
    }

    public static String billingPeriodMonthsAgo(int months) {
        Instant then = Instant.now().minus(java.time.Duration.ofDays(30L * months));
        return DateTimeFormatter.ofPattern("yyyy-MM")
            .withZone(ZoneOffset.UTC).format(then);
    }
}
