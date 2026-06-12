package com.microservices.courseservice.service;

import com.microservices.courseservice.model.ai.AiModelPricing;
import org.springframework.stereotype.Service;

@Service
public class AiUsageCostCalculator {

    public Long calculateCostMicros(
            AiModelPricing pricing,
            Integer inputTokens,
            Integer outputTokens,
            Integer cachedTokens,
            Integer reasoningTokens) {
        if (pricing == null) {
            return null;
        }
        if (inputTokens == null && outputTokens == null && cachedTokens == null && reasoningTokens == null) {
            return null;
        }

        long total = 0;
        boolean any = false;

        if (inputTokens != null) {
            total += tokenComponentCost(inputTokens, pricing.getInputPricePerMillionMicros());
            any = true;
        }
        if (outputTokens != null) {
            total += tokenComponentCost(outputTokens, pricing.getOutputPricePerMillionMicros());
            any = true;
        }
        if (cachedTokens != null && pricing.getCachedPricePerMillionMicros() != null) {
            total += tokenComponentCost(cachedTokens, pricing.getCachedPricePerMillionMicros());
            any = true;
        }
        if (reasoningTokens != null && pricing.getReasoningPricePerMillionMicros() != null) {
            total += tokenComponentCost(reasoningTokens, pricing.getReasoningPricePerMillionMicros());
            any = true;
        }

        return any ? total : null;
    }

    static long tokenComponentCost(long tokens, long pricePerMillionMicros) {
        if (tokens <= 0) {
            return 0;
        }
        return (tokens * pricePerMillionMicros) / 1_000_000L;
    }
}
