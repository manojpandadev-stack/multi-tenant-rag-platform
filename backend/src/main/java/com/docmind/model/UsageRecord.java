package com.docmind.model;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.UuidGenerator;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "usage_records")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class UsageRecord {

    @Id
    @GeneratedValue
    @UuidGenerator
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "org_id", nullable = false)
    private Organization organization;

    @Column(name = "event_type", nullable = false)
    private String eventType;

    @Column(name = "tokens_consumed")
    @Builder.Default
    private Integer tokensConsumed = 0;

    @Column(name = "is_cache_hit")
    @Builder.Default
    private Boolean isCacheHit = false;

    @Column(name = "estimated_cost_cents")
    @Builder.Default
    private Integer estimatedCostCents = 0;

    @Column(name = "created_at")
    private Instant createdAt;

    @PrePersist
    void prePersist() {
        createdAt = Instant.now();
    }
}
