package com.microservices.courseservice.service;

import com.microservices.courseservice.model.ai.AiModelPricing;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

class AiUsageCostCalculatorTest {

    private final AiUsageCostCalculator calculator = new AiUsageCostCalculator();

    @Test
    void calculatesInputOutputAndCachedCost() {
        AiModelPricing pricing = AiModelPricing.builder()
                .modelId("gemini-2.5-flash")
                .inputPricePerMillionMicros(150_000L)
                .outputPricePerMillionMicros(600_000L)
                .cachedPricePerMillionMicros(37_500L)
                .currency("USD")
                .effectiveFrom(Instant.parse("2026-01-01T00:00:00Z"))
                .build();

        Long cost = calculator.calculateCostMicros(pricing, 1_000_000, 500_000, 100_000, null);

        assertThat(cost).isEqualTo(150_000L + 300_000L + 3_750L);
    }

    @Test
    void returnsNullWhenAllTokenFieldsMissing() {
        AiModelPricing pricing = AiModelPricing.builder()
                .modelId("gemini-2.5-flash")
                .inputPricePerMillionMicros(150_000L)
                .outputPricePerMillionMicros(600_000L)
                .currency("USD")
                .effectiveFrom(Instant.parse("2026-01-01T00:00:00Z"))
                .build();

        assertThat(calculator.calculateCostMicros(pricing, null, null, null, null)).isNull();
    }
}
