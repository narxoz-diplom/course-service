package com.microservices.courseservice.model.ai;

import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;
import java.time.LocalDate;

@Entity
@Table(
        name = "ai_usage_aggregates",
        uniqueConstraints = @UniqueConstraint(
                columnNames = {"teacher_id", "model_id", "period_type", "period_start"}
        )
)
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class AiUsageAggregate {

    @Id
    @GeneratedValue(strategy = jakarta.persistence.GenerationType.IDENTITY)
    private Long id;

    @Column(name = "teacher_id", nullable = false, length = 128)
    private String teacherId;

    @Column(name = "model_id", nullable = false, length = 64)
    private String modelId;

    @Column(name = "period_type", nullable = false, length = 16)
    private String periodType;

    @Column(name = "period_start", nullable = false)
    private LocalDate periodStart;

    @Column(name = "input_tokens", nullable = false)
    @Builder.Default
    private long inputTokens = 0;

    @Column(name = "output_tokens", nullable = false)
    @Builder.Default
    private long outputTokens = 0;

    @Column(name = "cached_tokens", nullable = false)
    @Builder.Default
    private long cachedTokens = 0;

    @Column(name = "reasoning_tokens", nullable = false)
    @Builder.Default
    private long reasoningTokens = 0;

    @Column(name = "total_tokens", nullable = false)
    @Builder.Default
    private long totalTokens = 0;

    @Column(name = "cost_micros", nullable = false)
    @Builder.Default
    private long costMicros = 0;

    @Column(nullable = false, length = 3)
    @Builder.Default
    private String currency = "USD";

    @Column(name = "generation_count", nullable = false)
    @Builder.Default
    private int generationCount = 0;

    @Column(name = "ledger_entry_count", nullable = false)
    @Builder.Default
    private int ledgerEntryCount = 0;

    @Column(name = "updated_at", nullable = false)
    @Builder.Default
    private Instant updatedAt = Instant.now();

    @PreUpdate
    void touch() {
        updatedAt = Instant.now();
    }
}
