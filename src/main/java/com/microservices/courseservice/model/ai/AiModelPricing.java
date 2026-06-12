package com.microservices.courseservice.model.ai;

import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;

@Entity
@Table(name = "ai_model_pricing")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class AiModelPricing {

    @Id
    @GeneratedValue(strategy = jakarta.persistence.GenerationType.IDENTITY)
    private Long id;

    @Column(name = "model_id", nullable = false, length = 64)
    private String modelId;

    @Column(name = "input_price_per_million_micros", nullable = false)
    private long inputPricePerMillionMicros;

    @Column(name = "output_price_per_million_micros", nullable = false)
    private long outputPricePerMillionMicros;

    @Column(name = "cached_price_per_million_micros")
    private Long cachedPricePerMillionMicros;

    @Column(name = "reasoning_price_per_million_micros")
    private Long reasoningPricePerMillionMicros;

    @Column(nullable = false, length = 3)
    @Builder.Default
    private String currency = "USD";

    @Column(name = "effective_from", nullable = false)
    private Instant effectiveFrom;

    @Column(name = "effective_to")
    private Instant effectiveTo;

    @Column(name = "created_at", nullable = false)
    @Builder.Default
    private Instant createdAt = Instant.now();
}
