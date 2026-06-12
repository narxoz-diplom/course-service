package com.microservices.courseservice.service;

import com.microservices.courseservice.dto.ai.GenerationUsageSummaryDto;
import com.microservices.courseservice.model.ai.GenerationRun;
import com.microservices.courseservice.model.ai.GenerationRunStatus;
import com.microservices.courseservice.model.ai.GenerationType;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

class AiGenerationMetricsServiceTest {

    private SimpleMeterRegistry registry;
    private AiGenerationMetricsService service;

    @BeforeEach
    void setUp() {
        registry = new SimpleMeterRegistry();
        service = new AiGenerationMetricsService(registry);
    }

    @Test
    void recordsRunAndTokenMetricsOnSuccess() {
        GenerationRun run = GenerationRun.builder()
                .id("gr_test")
                .teacherId("teacher-1")
                .generationType(GenerationType.LESSON_FROM_FILES)
                .requestedModelId("gemini-2.5-flash")
                .actualModelId("gemini-2.5-flash")
                .actualProvider("google")
                .status(GenerationRunStatus.SUCCEEDED)
                .createdAt(Instant.parse("2026-06-01T10:00:00Z"))
                .finishedAt(Instant.parse("2026-06-01T10:00:05Z"))
                .build();

        GenerationUsageSummaryDto summary = GenerationUsageSummaryDto.builder()
                .generationRunId("gr_test")
                .modelId("gemini-2.5-flash")
                .inputTokens(100)
                .outputTokens(200)
                .totalTokens(300)
                .costMicros(1500L)
                .currency("USD")
                .build();

        service.recordRunFinished(run, summary);
        service.recordQuotaBlocked("gpt-4o", "teacher");

        assertThat(registry.find("ai.generation.runs").counter().count()).isEqualTo(1.0);
        assertThat(registry.find("ai.generation.tokens").tags("token_type", "total").counter().count())
                .isEqualTo(300.0);
        assertThat(registry.find("ai.generation.cost_micros").counter().count()).isEqualTo(1500.0);
        assertThat(registry.find("ai.generation.quota_blocked").counter().count()).isEqualTo(1.0);
        assertThat(registry.find("ai.generation.latency").timer().count()).isEqualTo(1);
    }
}
