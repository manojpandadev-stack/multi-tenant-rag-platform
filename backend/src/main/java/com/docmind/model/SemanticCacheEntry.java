package com.docmind.model;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.annotations.UuidGenerator;
import org.hibernate.type.SqlTypes;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "semantic_cache",
    indexes = {
        @Index(name = "idx_cache_org_scope", columnList = "org_id, scopeHash"),
        @Index(name = "idx_cache_expires", columnList = "expiresAt")
    })
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class SemanticCacheEntry {

    @Id
    @GeneratedValue
    @UuidGenerator
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "org_id", nullable = false)
    private Organization organization;

    @Column(name = "scope_hash", nullable = false, length = 64)
    private String scopeHash;

    @Column(name = "query_text", nullable = false, columnDefinition = "text")
    private String queryText;

    @JdbcTypeCode(SqlTypes.VECTOR)
    @Column(name = "query_embedding", columnDefinition = "vector(1536)", nullable = false)
    private float[] queryEmbedding;

    @Column(name = "answer_text", nullable = false, columnDefinition = "text")
    private String answerText;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "source_chunk_ids", columnDefinition = "jsonb")
    private String sourceChunkIds;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "source_doc_ids", columnDefinition = "jsonb")
    private String sourceDocIds;

    @Column(name = "model_used", length = 100)
    private String modelUsed;

    @Column(name = "token_count")
    @Builder.Default
    private Integer tokenCount = 0;

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    @Column(name = "created_at")
    private Instant createdAt;

    @PrePersist
    void prePersist() {
        createdAt = Instant.now();
    }

    public UUID getOrgId() {
        return organization != null ? organization.getId() : null;
    }
}
