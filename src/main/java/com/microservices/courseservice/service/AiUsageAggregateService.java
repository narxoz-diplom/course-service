package com.microservices.courseservice.service;

import com.microservices.courseservice.model.ai.AiUsageAggregate;
import com.microservices.courseservice.model.ai.TokenUsageLedger;
import com.microservices.courseservice.repository.AiUsageAggregateRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;

@Service
@RequiredArgsConstructor
public class AiUsageAggregateService {

    static final String PERIOD_DAILY = "daily";
    static final String PERIOD_MONTHLY = "monthly";

    private final AiUsageAggregateRepository aggregateRepository;

    @Transactional
    public void applyLedgerEntry(String teacherId, TokenUsageLedger ledger, boolean incrementGenerationCount) {
        LocalDate day = ledger.getCreatedAt().atZone(ZoneOffset.UTC).toLocalDate();
        applyForPeriod(teacherId, ledger, PERIOD_DAILY, day, incrementGenerationCount);
        applyForPeriod(teacherId, ledger, PERIOD_MONTHLY, day.withDayOfMonth(1), incrementGenerationCount);
    }

    private void applyForPeriod(
            String teacherId,
            TokenUsageLedger ledger,
            String periodType,
            LocalDate periodStart,
            boolean incrementGenerationCount) {
        AiUsageAggregate aggregate = aggregateRepository
                .findByTeacherIdAndModelIdAndPeriodTypeAndPeriodStart(
                        teacherId, ledger.getModelId(), periodType, periodStart)
                .orElseGet(() -> AiUsageAggregate.builder()
                        .teacherId(teacherId)
                        .modelId(ledger.getModelId())
                        .periodType(periodType)
                        .periodStart(periodStart)
                        .currency(ledger.getCurrency())
                        .build());

        aggregate.setInputTokens(aggregate.getInputTokens() + longOrZero(ledger.getInputTokens()));
        aggregate.setOutputTokens(aggregate.getOutputTokens() + longOrZero(ledger.getOutputTokens()));
        aggregate.setCachedTokens(aggregate.getCachedTokens() + longOrZero(ledger.getCachedTokens()));
        aggregate.setReasoningTokens(aggregate.getReasoningTokens() + longOrZero(ledger.getReasoningTokens()));
        aggregate.setTotalTokens(aggregate.getTotalTokens() + longOrZero(ledger.getTotalTokens()));
        aggregate.setCostMicros(aggregate.getCostMicros() + longOrZero(ledger.getCostMicros()));
        aggregate.setLedgerEntryCount(aggregate.getLedgerEntryCount() + 1);
        if (incrementGenerationCount) {
            aggregate.setGenerationCount(aggregate.getGenerationCount() + 1);
        }
        aggregate.setUpdatedAt(Instant.now());
        aggregateRepository.save(aggregate);
    }

    private static long longOrZero(Integer value) {
        return value == null ? 0L : value.longValue();
    }

    private static long longOrZero(Long value) {
        return value == null ? 0L : value;
    }
}
