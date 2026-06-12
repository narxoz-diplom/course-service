package com.microservices.courseservice.service;

import com.microservices.courseservice.ai.AiModelConstants;
import com.microservices.courseservice.exception.AiModelException;
import com.microservices.courseservice.model.ai.AiModel;
import com.microservices.courseservice.model.ai.AiModelPolicy;
import com.microservices.courseservice.model.ai.AiUsageAggregate;
import com.microservices.courseservice.repository.AiUsageAggregateRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AiQuotaServiceTest {

    @Mock private AiUsageAggregateRepository aggregateRepository;

    private AiQuotaService service;

    @BeforeEach
    void setUp() {
        service = new AiQuotaService(aggregateRepository);
    }

    @Test
    void generationBlockedWhenMonthlyQuotaExceeded() {
        LocalDate monthStart = LocalDate.now(ZoneOffset.UTC).withDayOfMonth(1);
        when(aggregateRepository.findByTeacherIdAndModelIdAndPeriodTypeAndPeriodStart(
                eq("teacher-1"), eq("gpt-4o"), eq("monthly"), eq(monthStart)))
                .thenReturn(Optional.of(AiUsageAggregate.builder().totalTokens(500_000L).build()));
        when(aggregateRepository.findByTeacherIdAndModelIdAndPeriodTypeAndPeriodStart(
                eq("teacher-1"), eq("gpt-4o"), eq("daily"), eq(LocalDate.now(ZoneOffset.UTC))))
                .thenReturn(Optional.empty());

        AiModel model = model("gpt-4o");
        AiModelPolicy policy = policy(500_000L, null);

        assertThatThrownBy(() -> service.assertWithinQuota("teacher-1", model, policy))
                .isInstanceOf(AiModelException.class)
                .satisfies(ex -> assertThat(((AiModelException) ex).getCode())
                        .isEqualTo(AiModelException.Code.QUOTA_EXCEEDED));
    }

    @Test
    void idempotentQuotaCheckUsesAggregateUsageNotDoubleReserve() {
        LocalDate monthStart = LocalDate.now(ZoneOffset.UTC).withDayOfMonth(1);
        when(aggregateRepository.findByTeacherIdAndModelIdAndPeriodTypeAndPeriodStart(
                eq("teacher-1"), eq("gpt-4o"), eq("monthly"), eq(monthStart)))
                .thenReturn(Optional.of(AiUsageAggregate.builder().totalTokens(100_000L).build()));
        when(aggregateRepository.findByTeacherIdAndModelIdAndPeriodTypeAndPeriodStart(
                eq("teacher-1"), eq("gpt-4o"), eq("daily"), eq(LocalDate.now(ZoneOffset.UTC))))
                .thenReturn(Optional.empty());

        AiQuotaService.QuotaEvaluation evaluation = service.evaluate("teacher-1", model("gpt-4o"), policy(500_000L, null));

        assertThat(evaluation.monthlyRemaining()).isEqualTo(400_000L);
        assertThat(evaluation.blocked()).isFalse();
    }

    @Test
    void dailyQuotaBlocksBeforeMonthly() {
        LocalDate today = LocalDate.now(ZoneOffset.UTC);
        when(aggregateRepository.findByTeacherIdAndModelIdAndPeriodTypeAndPeriodStart(
                eq("teacher-1"), eq("gpt-4o"), eq("monthly"), eq(today.withDayOfMonth(1))))
                .thenReturn(Optional.empty());
        when(aggregateRepository.findByTeacherIdAndModelIdAndPeriodTypeAndPeriodStart(
                eq("teacher-1"), eq("gpt-4o"), eq("daily"), eq(today)))
                .thenReturn(Optional.of(AiUsageAggregate.builder().totalTokens(50_000L).build()));

        AiQuotaService.QuotaEvaluation evaluation =
                service.evaluate("teacher-1", model("gpt-4o"), policy(null, 50_000L));

        assertThat(evaluation.blocked()).isTrue();
        assertThat(evaluation.blockReason()).contains("Daily");
    }

    private static AiModel model(String id) {
        return AiModel.builder()
                .id(id)
                .provider("openai")
                .providerModelId(id)
                .displayName(id)
                .tier("quality")
                .selectable(true)
                .enabled(true)
                .capabilities(java.util.Set.of(AiModelConstants.CAPABILITY_COURSE_GENERATION))
                .build();
    }

    private static AiModelPolicy policy(Long monthly, Long daily) {
        return AiModelPolicy.builder()
                .modelId("gpt-4o")
                .allowedRole("teacher")
                .capability(AiModelConstants.CAPABILITY_COURSE_GENERATION)
                .monthlyTokenQuota(monthly)
                .dailyTokenQuota(daily)
                .build();
    }
}
