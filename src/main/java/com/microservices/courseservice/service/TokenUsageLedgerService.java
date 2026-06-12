package com.microservices.courseservice.service;

import com.microservices.courseservice.dto.ai.GenerationUsageSummaryDto;
import com.microservices.courseservice.dto.ai.RagLlmUsageDto;
import com.microservices.courseservice.model.ai.GenerationRun;
import com.microservices.courseservice.model.ai.LedgerEntryStatus;
import com.microservices.courseservice.model.ai.TokenUsageLedger;
import com.microservices.courseservice.model.ai.AiModelPricing;
import com.microservices.courseservice.repository.TokenUsageLedgerRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Locale;

@Service
@RequiredArgsConstructor
public class TokenUsageLedgerService {

    private final TokenUsageLedgerRepository ledgerRepository;
    private final AiModelCatalogService aiModelCatalogService;
    private final AiUsageCostCalculator costCalculator;
    private final AiUsageAggregateService aggregateService;

    @Transactional
    public TokenUsageLedger recordUsage(GenerationRun run, RagLlmUsageDto usage) {
        if (usage == null) {
            return null;
        }

        List<TokenUsageLedger> existingEntries =
                ledgerRepository.findByGenerationRunIdOrderByCreatedAtAsc(run.getId());
        int attemptNumber = resolveLedgerAttemptNumber(existingEntries, usage);
        if (ledgerRepository.existsByGenerationRunIdAndAttemptNumber(run.getId(), attemptNumber)) {
            String providerRequestId = usage.getProviderRequestId();
            if (providerRequestId != null && !providerRequestId.isBlank()) {
                return existingEntries.stream()
                        .filter(entry -> providerRequestId.equals(entry.getProviderRequestId()))
                        .findFirst()
                        .orElseGet(() -> existingEntries.stream()
                                .filter(entry -> entry.getAttemptNumber() == attemptNumber)
                                .findFirst()
                                .orElseThrow());
            }
            return existingEntries.stream()
                    .filter(entry -> entry.getAttemptNumber() == attemptNumber)
                    .findFirst()
                    .orElseThrow();
        }

        String modelId = firstNonBlank(usage.getLlmModelId(), run.getActualModelId(), run.getRequestedModelId());
        Instant pricedAt = Instant.now();
        AiModelPricing pricing = aiModelCatalogService.findActivePricing(modelId, pricedAt).orElse(null);

        LedgerEntryStatus status = mapLedgerStatus(usage.getUsageSource());
        Long costMicros = costCalculator.calculateCostMicros(
                pricing,
                usage.getInputTokens(),
                usage.getOutputTokens(),
                usage.getCachedTokens(),
                usage.getReasoningTokens());

        TokenUsageLedger ledger = TokenUsageLedger.builder()
                .generationRunId(run.getId())
                .providerRequestId(usage.getProviderRequestId())
                .provider(requireNonBlank(usage.getProvider(), "unknown"))
                .modelId(modelId)
                .providerModelId(usage.getProviderModelId())
                .inputTokens(usage.getInputTokens())
                .outputTokens(usage.getOutputTokens())
                .cachedTokens(usage.getCachedTokens())
                .reasoningTokens(usage.getReasoningTokens())
                .totalTokens(usage.getTotalTokens())
                .pricingId(pricing != null ? pricing.getId() : null)
                .costMicros(costMicros)
                .currency(pricing != null ? pricing.getCurrency() : "USD")
                .usageSource(normalizeUsageSource(usage.getUsageSource()))
                .attemptNumber(attemptNumber)
                .status(status)
                .usedFallback(Boolean.TRUE.equals(usage.getUsedFallback()))
                .fallbackFromModelId(usage.getFallbackFromModelId())
                .createdAt(pricedAt)
                .build();

        TokenUsageLedger saved = ledgerRepository.save(ledger);
        aggregateService.applyLedgerEntry(run.getTeacherId(), saved, attemptNumber == 1);
        return saved;
    }

    @Transactional(readOnly = true)
    public List<TokenUsageLedger> findByRunId(String generationRunId) {
        return ledgerRepository.findByGenerationRunIdOrderByCreatedAtAsc(generationRunId);
    }

    @Transactional(readOnly = true)
    public GenerationUsageSummaryDto summarizeRun(String generationRunId) {
        List<TokenUsageLedger> entries = findByRunId(generationRunId);
        if (entries.isEmpty()) {
            return null;
        }

        int input = 0;
        int output = 0;
        int total = 0;
        long cost = 0;
        boolean anyInput = false;
        boolean anyOutput = false;
        boolean anyTotal = false;
        boolean anyCost = false;
        String modelId = entries.get(entries.size() - 1).getModelId();
        String currency = entries.get(entries.size() - 1).getCurrency();
        String usageSource = entries.get(entries.size() - 1).getUsageSource();

        for (TokenUsageLedger entry : entries) {
            if (entry.getInputTokens() != null) {
                input += entry.getInputTokens();
                anyInput = true;
            }
            if (entry.getOutputTokens() != null) {
                output += entry.getOutputTokens();
                anyOutput = true;
            }
            if (entry.getTotalTokens() != null) {
                total += entry.getTotalTokens();
                anyTotal = true;
            }
            if (entry.getCostMicros() != null) {
                cost += entry.getCostMicros();
                anyCost = true;
            }
        }

        return GenerationUsageSummaryDto.builder()
                .generationRunId(generationRunId)
                .modelId(modelId)
                .inputTokens(anyInput ? input : null)
                .outputTokens(anyOutput ? output : null)
                .totalTokens(anyTotal ? total : null)
                .costMicros(anyCost ? cost : null)
                .currency(currency)
                .usageSource(usageSource)
                .build();
    }

    private static LedgerEntryStatus mapLedgerStatus(String usageSource) {
        String normalized = normalizeUsageSource(usageSource);
        return switch (normalized) {
            case "estimated" -> LedgerEntryStatus.ESTIMATED;
            case "reconciled" -> LedgerEntryStatus.RECONCILED;
            default -> LedgerEntryStatus.RECORDED;
        };
    }

    private static String normalizeUsageSource(String usageSource) {
        if (usageSource == null || usageSource.isBlank()) {
            return "provider_reported";
        }
        return usageSource.trim().toLowerCase(Locale.ROOT);
    }

    private static String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value.trim();
            }
        }
        throw new IllegalArgumentException("modelId is required for token usage ledger");
    }

    private static String requireNonBlank(String value, String fallback) {
        if (value == null || value.isBlank()) {
            return fallback;
        }
        return value.trim();
    }

    private static int resolveLedgerAttemptNumber(List<TokenUsageLedger> existingEntries, RagLlmUsageDto usage) {
        int requested = usage.getAttemptNumber() != null && usage.getAttemptNumber() > 0
                ? usage.getAttemptNumber()
                : 1;
        String providerRequestId = usage.getProviderRequestId();
        if (providerRequestId != null && !providerRequestId.isBlank()) {
            boolean sameRequestRecorded = existingEntries.stream()
                    .anyMatch(entry -> providerRequestId.equals(entry.getProviderRequestId()));
            if (sameRequestRecorded) {
                return existingEntries.stream()
                        .filter(entry -> providerRequestId.equals(entry.getProviderRequestId()))
                        .mapToInt(TokenUsageLedger::getAttemptNumber)
                        .findFirst()
                        .orElse(requested);
            }
        }
        boolean attemptTaken = existingEntries.stream()
                .anyMatch(entry -> entry.getAttemptNumber() == requested);
        if (attemptTaken) {
            return existingEntries.stream()
                    .mapToInt(TokenUsageLedger::getAttemptNumber)
                    .max()
                    .orElse(0) + 1;
        }
        return requested;
    }
}
