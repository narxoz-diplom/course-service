package com.microservices.courseservice.service;

import com.microservices.courseservice.dto.ai.GenerationUsageSummaryDto;
import com.microservices.courseservice.model.ai.GenerationRun;
import com.microservices.courseservice.model.ai.GenerationRunStatus;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tags;
import io.micrometer.core.instrument.Timer;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;

@Service
@RequiredArgsConstructor
public class AiGenerationMetricsService {

    private final MeterRegistry meterRegistry;

    public void recordQuotaBlocked(String modelId, String role) {
        meterRegistry.counter(
                "ai.generation.quota_blocked",
                Tags.of("model", safeTag(modelId), "role", safeTag(role))
        ).increment();
    }

    public void recordRunFinished(GenerationRun run, GenerationUsageSummaryDto summary) {
        if (run == null) {
            return;
        }
        String model = run.getActualModelId() != null ? run.getActualModelId() : run.getRequestedModelId();
        String provider = run.getActualProvider() != null ? run.getActualProvider() : "unknown";
        Tags tags = Tags.of(
                "provider", safeTag(provider),
                "model", safeTag(model),
                "status", safeTag(run.getStatus() != null ? run.getStatus().name() : GenerationRunStatus.FAILED.name()),
                "generation_type", safeTag(run.getGenerationType() != null ? run.getGenerationType().name() : "unknown"));

        meterRegistry.counter("ai.generation.runs", tags).increment();

        if (summary != null) {
            if (summary.getTotalTokens() != null && summary.getTotalTokens() > 0) {
                meterRegistry.counter("ai.generation.tokens", tags.and("token_type", "total"))
                        .increment(summary.getTotalTokens());
            }
            if (summary.getInputTokens() != null && summary.getInputTokens() > 0) {
                meterRegistry.counter("ai.generation.tokens", tags.and("token_type", "input"))
                        .increment(summary.getInputTokens());
            }
            if (summary.getOutputTokens() != null && summary.getOutputTokens() > 0) {
                meterRegistry.counter("ai.generation.tokens", tags.and("token_type", "output"))
                        .increment(summary.getOutputTokens());
            }
            if (summary.getCostMicros() != null && summary.getCostMicros() > 0) {
                meterRegistry.counter(
                        "ai.generation.cost_micros",
                        tags.and("currency", safeTag(summary.getCurrency()))
                ).increment(summary.getCostMicros());
            }
        }

        Duration latency = latencyFor(run);
        if (!latency.isZero()) {
            Timer.builder("ai.generation.latency")
                    .tags(tags)
                    .register(meterRegistry)
                    .record(latency);
        }
    }

    private static Duration latencyFor(GenerationRun run) {
        Instant started = run.getCreatedAt();
        Instant finished = run.getFinishedAt();
        if (started == null || finished == null || finished.isBefore(started)) {
            return Duration.ZERO;
        }
        return Duration.between(started, finished);
    }

    private static String safeTag(String value) {
        if (value == null || value.isBlank()) {
            return "unknown";
        }
        return value.trim();
    }
}
