package com.docmind.model;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.UuidGenerator;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "organizations")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Organization {

    @Id
    @GeneratedValue
    @UuidGenerator
    private UUID id;

    @Column(nullable = false)
    private String name;

    @Column(nullable = false, unique = true)
    private String slug;

    // Chunking configuration (per-org overrides)
    @Column(name = "chunking_strategy")
    @Builder.Default
    private String chunkingStrategy = "recursive";

    @Column(name = "chunk_size")
    @Builder.Default
    private Integer chunkSize = 512;

    @Column(name = "chunk_overlap")
    @Builder.Default
    private Integer chunkOverlap = 50;

    // Retrieval strategy: vector-only | hybrid | hybrid+rerank
    @Column(name = "retrieval_strategy")
    @Builder.Default
    private String retrievalStrategy = "vector-only";

    // Billing plan
    @Enumerated(EnumType.STRING)
    @Column(name = "plan_tier", nullable = false)
    @Builder.Default
    private PlanTier planTier = PlanTier.FREE;

    // Quota limits (per billing period, -1 = unlimited)
    @Column(name = "monthly_query_limit")
    @Builder.Default
    private Integer monthlyQueryLimit = 1000;

    @Column(name = "monthly_doc_limit")
    @Builder.Default
    private Integer monthlyDocLimit = 50;

    @Column(name = "monthly_storage_limit_bytes")
    @Builder.Default
    private Long monthlyStorageLimitBytes = 500L * 1024 * 1024; // 500MB

    @Column(name = "created_at")
    private Instant createdAt;

    @Column(name = "updated_at")
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

    public UUID getOrgId() {
        return id;
    }

    public enum PlanTier {
        FREE,
        PRO,
        ENTERPRISE
    }
}
