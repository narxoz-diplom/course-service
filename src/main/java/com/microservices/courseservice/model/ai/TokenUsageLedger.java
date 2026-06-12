package com.microservices.courseservice.model.ai;

import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;

@Entity
@Table(name = "token_usage_ledger")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class TokenUsageLedger {

    @Id
    @GeneratedValue(strategy = jakarta.persistence.GenerationType.IDENTITY)
    private Long id;

    @Column(name = "generation_run_id", nullable = false, length = 48)
    private String generationRunId;

    @Column(name = "provider_request_id", length = 128)
    private String providerRequestId;

    @Column(nullable = false, length = 32)
    private String provider;

    @Column(name = "model_id", nullable = false, length = 64)
    private String modelId;

    @Column(name = "provider_model_id", length = 128)
    private String providerModelId;

    @Column(name = "input_tokens")
    private Integer inputTokens;

    @Column(name = "output_tokens")
    private Integer outputTokens;

    @Column(name = "cached_tokens")
    private Integer cachedTokens;

    @Column(name = "reasoning_tokens")
    private Integer reasoningTokens;

    @Column(name = "total_tokens")
    private Integer totalTokens;

    @Column(name = "pricing_id")
    private Long pricingId;

    @Column(name = "cost_micros")
    private Long costMicros;

    @Column(nullable = false, length = 3)
    @Builder.Default
    private String currency = "USD";

    @Column(name = "usage_source", nullable = false, length = 24)
    private String usageSource;

    @Column(name = "attempt_number", nullable = false)
    @Builder.Default
    private int attemptNumber = 1;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    @Builder.Default
    private LedgerEntryStatus status = LedgerEntryStatus.RECORDED;

    @Column(name = "used_fallback", nullable = false)
    @Builder.Default
    private boolean usedFallback = false;

    @Column(name = "fallback_from_model_id", length = 64)
    private String fallbackFromModelId;

    @Column(name = "created_at", nullable = false)
    @Builder.Default
    private Instant createdAt = Instant.now();
}
