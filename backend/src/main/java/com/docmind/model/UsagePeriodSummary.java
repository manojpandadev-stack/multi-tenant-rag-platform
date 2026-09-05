package com.docmind.model;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.UuidGenerator;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "usage_periods", uniqueConstraints = {
    @UniqueConstraint(columnNames = {"org_id", "billing_period"})
})
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class UsagePeriodSummary {

    @Id
    @GeneratedValue
    @UuidGenerator
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "org_id", nullable = false)
    private Organization organization;

    /** Billing period in YYYY-MM format, e.g. "2026-08" */
    @Column(name = "billing_period", nullable = false, length = 7)
    private String billingPeriod;

    // Document metrics
    @Column(name = "documents_uploaded", nullable = false)
    @Builder.Default
    private Integer documentsUploaded = 0;

    @Column(name = "storage_bytes", nullable = false)
    @Builder.Default
    private Long storageBytes = 0L;

    // Query metrics
    @Column(name = "queries_total", nullable = false)
    @Builder.Default
    private Integer queriesTotal = 0;

    @Column(name = "queries_vector_only", nullable = false)
    @Builder.Default
    private Integer queriesVectorOnly = 0;

    @Column(name = "queries_hybrid", nullable = false)
    @Builder.Default
    private Integer queriesHybrid = 0;

    @Column(name = "queries_hybrid_rerank", nullable = false)
    @Builder.Default
    private Integer queriesHybridRerank = 0;

    // Token metrics
    @Column(name = "embedding_tokens", nullable = false)
    @Builder.Default
    private Long embeddingTokens = 0L;

    @Column(name = "llm_input_tokens", nullable = false)
    @Builder.Default
    private Long llmInputTokens = 0L;

    @Column(name = "llm_output_tokens", nullable = false)
    @Builder.Default
    private Long llmOutputTokens = 0L;

    // Cache metrics
    @Column(name = "cache_hits", nullable = false)
    @Builder.Default
    private Integer cacheHits = 0;

    @Column(name = "cache_misses", nullable = false)
    @Builder.Default
    private Integer cacheMisses = 0;

    // Reranking
    @Column(name = "rerank_calls", nullable = false)
    @Builder.Default
    private Integer rerankCalls = 0;

    // Cost
    @Column(name = "estimated_cost_cents", nullable = false)
    @Builder.Default
    private Long estimatedCostCents = 0L;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @PrePersist
    void prePersist() {
        createdAt = Instant.now();
        updatedAt = Instant.now();
    }

    @PreUpdate
    void preUpdate() {
        updatedAt = Instant.now();
    }
}
