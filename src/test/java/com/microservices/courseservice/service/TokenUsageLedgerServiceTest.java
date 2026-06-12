package com.microservices.courseservice.service;

import com.microservices.courseservice.config.AiFeatureProperties;
import com.microservices.courseservice.config.AiProviderProperties;
import com.microservices.courseservice.config.AiQuotaProperties;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import com.microservices.courseservice.dto.ai.RagLlmUsageDto;
import com.microservices.courseservice.model.ai.GenerationRun;
import com.microservices.courseservice.model.ai.GenerationRunStatus;
import com.microservices.courseservice.model.ai.GenerationType;
import com.microservices.courseservice.model.ai.AiModelPricing;
import com.microservices.courseservice.model.ai.LedgerEntryStatus;
import com.microservices.courseservice.model.ai.TokenUsageLedger;
import com.microservices.courseservice.repository.AiModelPolicyRepository;
import com.microservices.courseservice.repository.AiModelPricingRepository;
import com.microservices.courseservice.repository.AiModelRepository;
import com.microservices.courseservice.repository.AiUsageAggregateRepository;
import com.microservices.courseservice.repository.TokenUsageLedgerRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Pageable;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class TokenUsageLedgerServiceTest {

    @Mock private TokenUsageLedgerRepository ledgerRepository;
    @Mock private AiModelRepository aiModelRepository;
    @Mock private AiModelPricingRepository aiModelPricingRepository;
    @Mock private AiModelPolicyRepository aiModelPolicyRepository;
    @Mock private AiUsageAggregateRepository aggregateRepository;

    private TokenUsageLedgerService service;
    private AiUsageAggregateService aggregateService;

    @BeforeEach
    void setUp() {
        AiProviderProperties providerProperties = new AiProviderProperties();
        AiFeatureProperties featureProperties = new AiFeatureProperties();
        AiQuotaService quotaService = new AiQuotaService(aggregateRepository);
        AiQuotaProperties quotaProperties = new AiQuotaProperties();
        TeacherAiLimitService teacherAiLimitService = new TeacherAiLimitService(
                org.mockito.Mockito.mock(com.microservices.courseservice.repository.TeacherAiLimitRepository.class),
                aggregateRepository,
                quotaProperties);
        AiModelCatalogService catalogService = new AiModelCatalogService(
                aiModelRepository,
                aiModelPricingRepository,
                aiModelPolicyRepository,
                providerProperties,
                featureProperties,
                quotaService,
                teacherAiLimitService,
                new AiGenerationMetricsService(new SimpleMeterRegistry()));
        aggregateService = new AiUsageAggregateService(aggregateRepository);
        service = new TokenUsageLedgerService(
                ledgerRepository,
                catalogService,
                new AiUsageCostCalculator(),
                aggregateService);
    }

    private void stubPricing() {
        when(aiModelPricingRepository.findActivePricingCandidates(
                eq("gemini-2.5-flash"), any(), any(Pageable.class)))
                .thenReturn(List.of(pricing()));
    }

    @Test
    void successfulGenerationCreatesLedgerRecord() {
        GenerationRun run = baseRun();
        RagLlmUsageDto usage = usageDto(1);
        AiModelPricing pricing = pricing();

        when(ledgerRepository.existsByGenerationRunIdAndAttemptNumber("gr_1", 1)).thenReturn(false);
        stubPricing();
        when(ledgerRepository.save(any())).thenAnswer(invocation -> {
            TokenUsageLedger ledger = invocation.getArgument(0);
            ledger.setId(99L);
            return ledger;
        });

        TokenUsageLedger saved = service.recordUsage(run, usage);

        assertThat(saved.getGenerationRunId()).isEqualTo("gr_1");
        assertThat(saved.getInputTokens()).isEqualTo(1000);
        assertThat(saved.getOutputTokens()).isEqualTo(500);
        assertThat(saved.getCostMicros()).isEqualTo(450L);
        assertThat(saved.getStatus()).isEqualTo(LedgerEntryStatus.RECORDED);
        verify(aggregateRepository, times(2)).save(any());
    }

    @Test
    void failedProviderUsageStillRecorded() {
        GenerationRun run = baseRun();
        RagLlmUsageDto usage = usageDto(1);
        usage.setUsageSource("provider_reported");

        when(ledgerRepository.existsByGenerationRunIdAndAttemptNumber("gr_1", 1)).thenReturn(false);
        stubPricing();
        when(ledgerRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        TokenUsageLedger saved = service.recordUsage(run, usage);

        assertThat(saved.getInputTokens()).isEqualTo(1000);
        assertThat(saved.getUsageSource()).isEqualTo("provider_reported");
    }

    @Test
    void retryCreatesSeparateAttemptLedgerEntry() {
        GenerationRun run = baseRun();
        when(ledgerRepository.existsByGenerationRunIdAndAttemptNumber("gr_1", 1)).thenReturn(false);
        when(ledgerRepository.existsByGenerationRunIdAndAttemptNumber("gr_1", 2)).thenReturn(false);
        stubPricing();
        when(ledgerRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        service.recordUsage(run, usageDto(1));
        service.recordUsage(run, usageDto(2));

        ArgumentCaptor<TokenUsageLedger> captor = ArgumentCaptor.forClass(TokenUsageLedger.class);
        verify(ledgerRepository, times(2)).save(captor.capture());
        assertThat(captor.getAllValues()).extracting(TokenUsageLedger::getAttemptNumber)
                .containsExactly(1, 2);
    }

    @Test
    void duplicateAttemptDoesNotDoubleCharge() {
        GenerationRun run = baseRun();
        TokenUsageLedger existing = TokenUsageLedger.builder()
                .id(7L)
                .generationRunId("gr_1")
                .attemptNumber(1)
                .providerRequestId("provider-req-1")
                .inputTokens(1000)
                .build();

        RagLlmUsageDto usage = usageDto(1);
        usage.setProviderRequestId("provider-req-1");
        when(ledgerRepository.existsByGenerationRunIdAndAttemptNumber("gr_1", 1)).thenReturn(true);
        when(ledgerRepository.findByGenerationRunIdOrderByCreatedAtAsc("gr_1")).thenReturn(List.of(existing));

        TokenUsageLedger saved = service.recordUsage(run, usage);

        assertThat(saved.getId()).isEqualTo(7L);
        verify(ledgerRepository, times(0)).save(any());
        verify(aggregateRepository, times(0)).save(any());
    }

    private static GenerationRun baseRun() {
        return GenerationRun.builder()
                .id("gr_1")
                .teacherId("teacher-1")
                .courseId(24L)
                .generationType(GenerationType.LESSON_OUTLINE)
                .requestedModelId("gemini-2.5-flash")
                .status(GenerationRunStatus.RUNNING)
                .build();
    }

    private static RagLlmUsageDto usageDto(int attempt) {
        RagLlmUsageDto usage = new RagLlmUsageDto();
        usage.setLlmModelId("gemini-2.5-flash");
        usage.setProvider("google");
        usage.setProviderModelId("gemini-2.5-flash");
        usage.setInputTokens(1000);
        usage.setOutputTokens(500);
        usage.setTotalTokens(1500);
        usage.setUsageSource("provider_reported");
        usage.setAttemptNumber(attempt);
        return usage;
    }

    private static AiModelPricing pricing() {
        return AiModelPricing.builder()
                .id(1L)
                .modelId("gemini-2.5-flash")
                .inputPricePerMillionMicros(150_000L)
                .outputPricePerMillionMicros(600_000L)
                .currency("USD")
                .effectiveFrom(Instant.parse("2026-01-01T00:00:00Z"))
                .build();
    }
}
