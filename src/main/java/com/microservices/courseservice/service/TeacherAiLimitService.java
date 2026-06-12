package com.microservices.courseservice.service;

import com.microservices.courseservice.config.AiQuotaProperties;
import com.microservices.courseservice.dto.ai.TeacherAiLimitStatusDto;
import com.microservices.courseservice.dto.ai.UpdateTeacherAiLimitRequest;
import com.microservices.courseservice.exception.AiModelException;
import com.microservices.courseservice.model.ai.TeacherAiLimit;
import com.microservices.courseservice.repository.AiUsageAggregateRepository;
import com.microservices.courseservice.repository.TeacherAiLimitRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Optional;

@Service
@RequiredArgsConstructor
public class TeacherAiLimitService {

    private final TeacherAiLimitRepository teacherAiLimitRepository;
    private final AiUsageAggregateRepository aggregateRepository;
    private final AiQuotaProperties quotaProperties;

    public record UserQuotaEvaluation(
            boolean unlimited,
            boolean customOverride,
            Long monthlyLimit,
            long monthlyUsed,
            Long monthlyRemaining,
            Long dailyLimit,
            long dailyUsed,
            Long dailyRemaining,
            boolean blocked,
            String blockReason,
            String note) {}

    @Transactional(readOnly = true)
    public UserQuotaEvaluation evaluate(String teacherId, boolean isAdmin) {
        if (isAdmin) {
            return unlimitedEvaluation(teacherId, true, false, null);
        }

        Optional<TeacherAiLimit> override = teacherAiLimitRepository.findById(teacherId);
        if (override.isPresent() && override.get().isUnlimitedAccess()) {
            return unlimitedEvaluation(teacherId, true, true, override.get().getNote());
        }

        Long monthlyLimit = resolveMonthlyLimit(override.orElse(null));
        Long dailyLimit = resolveDailyLimit(override.orElse(null));
        LocalDate today = LocalDate.now(ZoneOffset.UTC);
        long monthlyUsed = sumUsage(teacherId, AiUsageAggregateService.PERIOD_MONTHLY, today.withDayOfMonth(1));
        long dailyUsed = sumUsage(teacherId, AiUsageAggregateService.PERIOD_DAILY, today);

        Long monthlyRemaining = Math.max(0L, monthlyLimit - monthlyUsed);
        Long dailyRemaining = Math.max(0L, dailyLimit - dailyUsed);

        boolean monthlyBlocked = monthlyUsed >= monthlyLimit;
        boolean dailyBlocked = dailyUsed >= dailyLimit;
        boolean blocked = monthlyBlocked || dailyBlocked;

        String blockReason = null;
        if (monthlyBlocked) {
            blockReason = "Monthly AI token limit exceeded for your account";
        } else if (dailyBlocked) {
            blockReason = "Daily AI token limit exceeded for your account";
        }

        return new UserQuotaEvaluation(
                false,
                override.isPresent(),
                monthlyLimit,
                monthlyUsed,
                monthlyRemaining,
                dailyLimit,
                dailyUsed,
                dailyRemaining,
                blocked,
                blockReason,
                override.map(TeacherAiLimit::getNote).orElse(null));
    }

    @Transactional(readOnly = true)
    public void assertWithinUserQuota(String teacherId, boolean isAdmin) {
        UserQuotaEvaluation evaluation = evaluate(teacherId, isAdmin);
        if (!evaluation.unlimited() && evaluation.blocked()) {
            throw new AiModelException(
                    AiModelException.Code.QUOTA_EXCEEDED,
                    HttpStatus.TOO_MANY_REQUESTS,
                    evaluation.blockReason());
        }
    }

    @Transactional(readOnly = true)
    public TeacherAiLimitStatusDto statusForTeacher(String teacherId, boolean isAdmin) {
        return toDto(teacherId, evaluate(teacherId, isAdmin));
    }

    @Transactional(readOnly = true)
    public TeacherAiLimitStatusDto adminGet(String teacherId) {
        return toDto(teacherId, evaluate(teacherId, false));
    }

    @Transactional
    public TeacherAiLimitStatusDto adminUpdate(String teacherId, String adminId, UpdateTeacherAiLimitRequest request) {
        if (request == null) {
            throw new IllegalArgumentException("Request body is required");
        }

        TeacherAiLimit entity = teacherAiLimitRepository.findById(teacherId)
                .orElseGet(() -> TeacherAiLimit.builder().teacherId(teacherId).build());

        if (request.getUnlimitedAccess() != null) {
            entity.setUnlimitedAccess(request.getUnlimitedAccess());
        }
        if (request.getMonthlyTokenLimit() != null) {
            if (request.getMonthlyTokenLimit() < 0) {
                throw new IllegalArgumentException("monthlyTokenLimit must be >= 0");
            }
            entity.setMonthlyTokenLimit(request.getMonthlyTokenLimit());
        }
        if (request.getDailyTokenLimit() != null) {
            if (request.getDailyTokenLimit() < 0) {
                throw new IllegalArgumentException("dailyTokenLimit must be >= 0");
            }
            entity.setDailyTokenLimit(request.getDailyTokenLimit());
        }
        if (request.getNote() != null) {
            entity.setNote(blankToNull(request.getNote()));
        }

        if (entity.isUnlimitedAccess()) {
            entity.setMonthlyTokenLimit(null);
            entity.setDailyTokenLimit(null);
        }

        entity.setUpdatedBy(adminId);
        entity.setUpdatedAt(Instant.now());
        teacherAiLimitRepository.save(entity);

        return adminGet(teacherId);
    }

    @Transactional
    public void adminDeleteOverride(String teacherId) {
        teacherAiLimitRepository.deleteById(teacherId);
    }

    private long sumUsage(String teacherId, String periodType, LocalDate periodStart) {
        return aggregateRepository.sumTotalTokensByTeacher(teacherId, periodType, periodStart);
    }

    private Long resolveMonthlyLimit(TeacherAiLimit override) {
        if (override != null && override.getMonthlyTokenLimit() != null) {
            return override.getMonthlyTokenLimit();
        }
        return quotaProperties.getTeacherDefaultMonthlyTokens();
    }

    private Long resolveDailyLimit(TeacherAiLimit override) {
        if (override != null && override.getDailyTokenLimit() != null) {
            return override.getDailyTokenLimit();
        }
        return quotaProperties.getTeacherDefaultDailyTokens();
    }

    private UserQuotaEvaluation unlimitedEvaluation(
            String teacherId, boolean unlimited, boolean customOverride, String note) {
        LocalDate today = LocalDate.now(ZoneOffset.UTC);
        long monthlyUsed = sumUsage(teacherId, AiUsageAggregateService.PERIOD_MONTHLY, today.withDayOfMonth(1));
        long dailyUsed = sumUsage(teacherId, AiUsageAggregateService.PERIOD_DAILY, today);
        return new UserQuotaEvaluation(
                unlimited,
                customOverride,
                null,
                monthlyUsed,
                null,
                null,
                dailyUsed,
                null,
                false,
                null,
                note);
    }

    private static TeacherAiLimitStatusDto toDto(String teacherId, UserQuotaEvaluation evaluation) {
        return TeacherAiLimitStatusDto.builder()
                .teacherId(teacherId)
                .unlimited(evaluation.unlimited())
                .customOverride(evaluation.customOverride())
                .monthlyLimit(evaluation.monthlyLimit())
                .monthlyUsed(evaluation.monthlyUsed())
                .monthlyRemaining(evaluation.monthlyRemaining())
                .dailyLimit(evaluation.dailyLimit())
                .dailyUsed(evaluation.dailyUsed())
                .dailyRemaining(evaluation.dailyRemaining())
                .blocked(evaluation.blocked())
                .blockReason(evaluation.blockReason())
                .note(evaluation.note())
                .build();
    }

    private static String blankToNull(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value.trim();
    }
}
