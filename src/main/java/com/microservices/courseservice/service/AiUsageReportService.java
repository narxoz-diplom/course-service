package com.microservices.courseservice.service;

import com.microservices.courseservice.ai.AiModelConstants;
import com.microservices.courseservice.dto.ai.usage.AdminAiUsageReportDto;
import com.microservices.courseservice.dto.ai.usage.AiQuotaUtilizationDto;
import com.microservices.courseservice.dto.ai.usage.AiUsageByModelDto;
import com.microservices.courseservice.dto.ai.usage.AiUsageByProviderDto;
import com.microservices.courseservice.dto.ai.usage.AiUsagePeriodDto;
import com.microservices.courseservice.dto.ai.usage.AiUsageRecentRunDto;
import com.microservices.courseservice.dto.ai.usage.AiUsageSummaryDto;
import com.microservices.courseservice.dto.ai.usage.AiUsageTimeSeriesPointDto;
import com.microservices.courseservice.dto.ai.usage.AiUsageTopUserDto;
import com.microservices.courseservice.dto.ai.usage.TeacherAiUsageReportDto;
import com.microservices.courseservice.model.ai.AiModel;
import com.microservices.courseservice.model.ai.AiModelPolicy;
import com.microservices.courseservice.repository.AiModelPolicyRepository;
import com.microservices.courseservice.repository.AiModelRepository;
import com.microservices.courseservice.repository.AiUsageReportRepository;
import com.microservices.courseservice.repository.AiUsageReportRepository.AiUsageFilter;
import com.microservices.courseservice.repository.AiUsageReportRepository.LedgerTotals;
import com.microservices.courseservice.repository.AiUsageReportRepository.ModelUsageRow;
import com.microservices.courseservice.repository.AiUsageReportRepository.ProviderUsageRow;
import com.microservices.courseservice.repository.AiUsageReportRepository.RecentRunRow;
import com.microservices.courseservice.repository.AiUsageReportRepository.TimeSeriesRow;
import com.microservices.courseservice.repository.AiUsageReportRepository.TopUserRow;
import com.microservices.courseservice.util.RoleUtil;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@Service
@RequiredArgsConstructor
public class AiUsageReportService {

    private static final int RECENT_RUNS_LIMIT = 20;
    private static final int TOP_USERS_LIMIT = 10;

    private final AiUsageReportRepository reportRepository;
    private final AiModelRepository aiModelRepository;
    private final AiModelPolicyRepository aiModelPolicyRepository;
    private final AiQuotaService aiQuotaService;

    @Transactional(readOnly = true)
    public TeacherAiUsageReportDto teacherUsage(Jwt jwt, LocalDate from, LocalDate to) {
        requireTeacherOrAdmin(jwt);
        String teacherId = jwt.getSubject();
        AiUsagePeriodDto period = resolvePeriod(from, to);
        AiUsageFilter filter = buildFilter(teacherId, null, null, null, null, null, period);

        return TeacherAiUsageReportDto.builder()
                .period(period)
                .summary(buildSummary(filter, false))
                .byModel(buildByModel(filter))
                .timeSeries(buildTimeSeries(filter))
                .recentRuns(buildRecentRuns(filter))
                .quotaUtilization(buildQuotaUtilization(teacherId, "teacher"))
                .build();
    }

    @Transactional(readOnly = true)
    public AdminAiUsageReportDto adminUsage(
            Jwt jwt,
            LocalDate from,
            LocalDate to,
            String userId,
            Long courseId,
            String modelId,
            String provider,
            String generationType,
            String status) {
        requireAdmin(jwt);
        AiUsagePeriodDto period = resolvePeriod(from, to);
        AiUsageFilter filter = buildFilter(userId, courseId, modelId, provider, generationType, status, period);

        return AdminAiUsageReportDto.builder()
                .period(period)
                .summary(buildSummary(filter, true))
                .byModel(buildByModel(filter))
                .byProvider(buildByProvider(filter))
                .topUsers(buildTopUsers(filter))
                .timeSeries(buildTimeSeries(filter))
                .quotaUtilization(buildQuotaUtilization(userId, "teacher"))
                .build();
    }

    private AiUsageSummaryDto buildSummary(AiUsageFilter filter, boolean includeUniqueTeachers) {
        LedgerTotals ledger = reportRepository.sumLedger(filter);
        int generationCount = reportRepository.countRuns(filter);
        int failedCount = reportRepository.countFailedRuns(filter);

        Long totalTokens = ledger.totalTokens() > 0 ? ledger.totalTokens() : null;
        Long inputTokens = ledger.inputTokens() > 0 ? ledger.inputTokens() : null;
        Long outputTokens = ledger.outputTokens() > 0 ? ledger.outputTokens() : null;
        Long cachedTokens = ledger.cachedTokens() > 0 ? ledger.cachedTokens() : null;
        Long reasoningTokens = ledger.reasoningTokens() > 0 ? ledger.reasoningTokens() : null;
        Long costMicros = ledger.costMicros() > 0 ? ledger.costMicros() : null;

        return AiUsageSummaryDto.builder()
                .totalTokens(totalTokens)
                .inputTokens(inputTokens)
                .outputTokens(outputTokens)
                .cachedTokens(cachedTokens)
                .reasoningTokens(reasoningTokens)
                .costMicros(costMicros)
                .currency(ledger.currency() != null ? ledger.currency() : "USD")
                .generationCount(generationCount > 0 ? generationCount : 0)
                .failedCount(failedCount > 0 ? failedCount : 0)
                .uniqueTeachers(includeUniqueTeachers ? reportRepository.countUniqueTeachers(filter) : null)
                .build();
    }

    private List<AiUsageByModelDto> buildByModel(AiUsageFilter filter) {
        Map<String, String> displayNames = loadDisplayNames();
        return reportRepository.usageByModel(filter).stream()
                .map(row -> AiUsageByModelDto.builder()
                        .modelId(row.modelId())
                        .displayName(displayNames.getOrDefault(row.modelId(), row.modelId()))
                        .totalTokens(row.totalTokens() > 0 ? row.totalTokens() : null)
                        .inputTokens(row.inputTokens() > 0 ? row.inputTokens() : null)
                        .outputTokens(row.outputTokens() > 0 ? row.outputTokens() : null)
                        .costMicros(row.costMicros() > 0 ? row.costMicros() : null)
                        .generationCount(row.generationCount() > 0 ? row.generationCount() : null)
                        .build())
                .toList();
    }

    private List<AiUsageByProviderDto> buildByProvider(AiUsageFilter filter) {
        return reportRepository.usageByProvider(filter).stream()
                .map(row -> AiUsageByProviderDto.builder()
                        .provider(row.provider())
                        .totalTokens(row.totalTokens() > 0 ? row.totalTokens() : null)
                        .costMicros(row.costMicros() > 0 ? row.costMicros() : null)
                        .build())
                .toList();
    }

    private List<AiUsageTopUserDto> buildTopUsers(AiUsageFilter filter) {
        return reportRepository.topUsers(filter, TOP_USERS_LIMIT).stream()
                .map(row -> AiUsageTopUserDto.builder()
                        .userId(row.userId())
                        .totalTokens(row.totalTokens() > 0 ? row.totalTokens() : null)
                        .costMicros(row.costMicros() > 0 ? row.costMicros() : null)
                        .generationCount(row.generationCount() > 0 ? row.generationCount() : null)
                        .build())
                .toList();
    }

    private List<AiUsageTimeSeriesPointDto> buildTimeSeries(AiUsageFilter filter) {
        List<TimeSeriesRow> rows = reportRepository.dailyTimeSeriesFromAggregates(filter);
        if (!rows.isEmpty()) {
            return rows.stream()
                    .map(row -> AiUsageTimeSeriesPointDto.builder()
                            .date(row.date())
                            .totalTokens(row.totalTokens() > 0 ? row.totalTokens() : null)
                            .costMicros(row.costMicros() > 0 ? row.costMicros() : null)
                            .build())
                    .toList();
        }
        return List.of();
    }

    private List<AiUsageRecentRunDto> buildRecentRuns(AiUsageFilter filter) {
        return reportRepository.recentRuns(filter, RECENT_RUNS_LIMIT).stream()
                .map(row -> AiUsageRecentRunDto.builder()
                        .generationRunId(row.runId())
                        .courseId(row.courseId())
                        .generationType(row.generationType())
                        .modelId(row.modelId())
                        .status(row.status())
                        .totalTokens(row.totalTokens() > 0 ? row.totalTokens() : null)
                        .costMicros(row.costMicros() > 0 ? row.costMicros() : null)
                        .createdAt(row.createdAt())
                        .build())
                .toList();
    }

    private List<AiQuotaUtilizationDto> buildQuotaUtilization(String userId, String role) {
        if (userId == null || userId.isBlank()) {
            return List.of();
        }
        Map<String, AiModel> modelsById = new HashMap<>();
        for (AiModel model : aiModelRepository.findAll()) {
            modelsById.put(model.getId(), model);
        }

        return aiModelPolicyRepository
                .findByAllowedRoleAndCapability(role, AiModelConstants.CAPABILITY_COURSE_GENERATION)
                .stream()
                .filter(policy -> policy.getMonthlyTokenQuota() != null || policy.getDailyTokenQuota() != null)
                .map(policy -> {
                    AiModel model = modelsById.get(policy.getModelId());
                    if (model == null) {
                        return null;
                    }
                    AiQuotaService.QuotaEvaluation evaluation = aiQuotaService.evaluate(userId, model, policy);
                    return AiQuotaUtilizationDto.builder()
                            .userId(userId)
                            .modelId(model.getId())
                            .displayName(model.getDisplayName())
                            .tier(model.getTier())
                            .monthlyLimitTokens(evaluation.monthlyLimit())
                            .monthlyUsedTokens(evaluation.monthlyUsed())
                            .monthlyRemainingTokens(evaluation.monthlyRemaining())
                            .dailyLimitTokens(evaluation.dailyLimit())
                            .dailyUsedTokens(evaluation.dailyUsed())
                            .dailyRemainingTokens(evaluation.dailyRemaining())
                            .blocked(evaluation.blocked())
                            .build();
                })
                .filter(java.util.Objects::nonNull)
                .toList();
    }

    private Map<String, String> loadDisplayNames() {
        Map<String, String> names = new HashMap<>();
        for (AiModel model : aiModelRepository.findAll()) {
            names.put(model.getId(), model.getDisplayName());
        }
        return names;
    }

    private static AiUsagePeriodDto resolvePeriod(LocalDate from, LocalDate to) {
        LocalDate effectiveTo = Optional.ofNullable(to).orElse(LocalDate.now(ZoneOffset.UTC));
        LocalDate effectiveFrom = Optional.ofNullable(from).orElse(effectiveTo.minusDays(29));
        if (effectiveFrom.isAfter(effectiveTo)) {
            effectiveFrom = effectiveTo;
        }
        return AiUsagePeriodDto.builder().from(effectiveFrom).to(effectiveTo).build();
    }

    private static AiUsageFilter buildFilter(
            String teacherId,
            Long courseId,
            String modelId,
            String provider,
            String generationType,
            String status,
            AiUsagePeriodDto period) {
        Instant from = period.getFrom().atStartOfDay(ZoneOffset.UTC).toInstant();
        Instant toExclusive = period.getTo().plusDays(1).atStartOfDay(ZoneOffset.UTC).toInstant();
        return new AiUsageFilter(
                blankToNull(teacherId),
                courseId,
                blankToNull(modelId),
                blankToNull(provider),
                blankToNull(generationType),
                blankToNull(status),
                from,
                toExclusive);
    }

    private static void requireTeacherOrAdmin(Jwt jwt) {
        if (jwt == null || (!RoleUtil.isTeacher(jwt) && !RoleUtil.isAdmin(jwt))) {
            throw new AccessDeniedException("Teacher or admin role required");
        }
    }

    private static void requireAdmin(Jwt jwt) {
        if (jwt == null || !RoleUtil.isAdmin(jwt)) {
            throw new AccessDeniedException("Admin role required");
        }
    }

    private static String blankToNull(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value.trim();
    }
}
