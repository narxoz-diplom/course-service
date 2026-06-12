package com.microservices.courseservice.service;

import com.microservices.courseservice.dto.ai.AiModelQuotaDto;
import com.microservices.courseservice.exception.AiModelException;
import com.microservices.courseservice.model.ai.AiModel;
import com.microservices.courseservice.model.ai.AiModelPolicy;
import com.microservices.courseservice.model.ai.AiUsageAggregate;
import com.microservices.courseservice.repository.AiUsageAggregateRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.ZoneOffset;

@Service
@RequiredArgsConstructor
public class AiQuotaService {

    private final AiUsageAggregateRepository aggregateRepository;

    public record QuotaEvaluation(
            Long monthlyLimit,
            long monthlyUsed,
            Long monthlyRemaining,
            Long dailyLimit,
            long dailyUsed,
            Long dailyRemaining,
            boolean blocked,
            String blockReason) {}

    @Transactional(readOnly = true)
    public QuotaEvaluation evaluate(String teacherId, AiModel model, AiModelPolicy policy) {
        LocalDate today = LocalDate.now(ZoneOffset.UTC);
        LocalDate monthStart = today.withDayOfMonth(1);

        long monthlyUsed = usageForPeriod(teacherId, model.getId(), AiUsageAggregateService.PERIOD_MONTHLY, monthStart);
        long dailyUsed = usageForPeriod(teacherId, model.getId(), AiUsageAggregateService.PERIOD_DAILY, today);

        Long monthlyLimit = policy.getMonthlyTokenQuota();
        Long dailyLimit = policy.getDailyTokenQuota();

        Long monthlyRemaining = monthlyLimit == null ? null : Math.max(0L, monthlyLimit - monthlyUsed);
        Long dailyRemaining = dailyLimit == null ? null : Math.max(0L, dailyLimit - dailyUsed);

        boolean monthlyBlocked = monthlyLimit != null && monthlyUsed >= monthlyLimit;
        boolean dailyBlocked = dailyLimit != null && dailyUsed >= dailyLimit;
        boolean blocked = monthlyBlocked || dailyBlocked;

        String blockReason = null;
        if (monthlyBlocked) {
            blockReason = "Monthly token quota exceeded for model: " + model.getId();
        } else if (dailyBlocked) {
            blockReason = "Daily token quota exceeded for model: " + model.getId();
        }

        return new QuotaEvaluation(
                monthlyLimit,
                monthlyUsed,
                monthlyRemaining,
                dailyLimit,
                dailyUsed,
                dailyRemaining,
                blocked,
                blockReason);
    }

    @Transactional(readOnly = true)
    public void assertWithinQuota(String teacherId, AiModel model, AiModelPolicy policy) {
        QuotaEvaluation evaluation = evaluate(teacherId, model, policy);
        if (evaluation.blocked()) {
            throw new AiModelException(
                    AiModelException.Code.QUOTA_EXCEEDED,
                    HttpStatus.TOO_MANY_REQUESTS,
                    evaluation.blockReason());
        }
    }

    public AiModelQuotaDto toQuotaDto(QuotaEvaluation evaluation) {
        if (evaluation.monthlyLimit() == null && evaluation.dailyLimit() == null) {
            return null;
        }

        AiModelQuotaDto.AiModelQuotaDtoBuilder builder = AiModelQuotaDto.builder()
                .blocked(evaluation.blocked());

        if (evaluation.monthlyLimit() != null) {
            builder.period("monthly")
                    .limitTokens(evaluation.monthlyLimit())
                    .usedTokens(evaluation.monthlyUsed())
                    .remainingTokens(evaluation.monthlyRemaining());
        }

        if (evaluation.dailyLimit() != null) {
            builder.daily(AiModelQuotaDto.builder()
                    .period("daily")
                    .limitTokens(evaluation.dailyLimit())
                    .usedTokens(evaluation.dailyUsed())
                    .remainingTokens(evaluation.dailyRemaining())
                    .blocked(evaluation.dailyRemaining() != null && evaluation.dailyRemaining() <= 0)
                    .build());
        }

        return builder.build();
    }

    private long usageForPeriod(String teacherId, String modelId, String periodType, LocalDate periodStart) {
        return aggregateRepository
                .findByTeacherIdAndModelIdAndPeriodTypeAndPeriodStart(teacherId, modelId, periodType, periodStart)
                .map(AiUsageAggregate::getTotalTokens)
                .orElse(0L);
    }
}
