package com.microservices.courseservice.repository;

import jakarta.persistence.EntityManager;
import jakarta.persistence.Query;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

@Repository
@RequiredArgsConstructor
public class AiUsageReportRepository {

    private final EntityManager entityManager;

    public record AiUsageFilter(
            String teacherId,
            Long courseId,
            String modelId,
            String provider,
            String generationType,
            String status,
            Instant from,
            Instant toExclusive) {}

    public record LedgerTotals(
            long inputTokens,
            long outputTokens,
            long cachedTokens,
            long reasoningTokens,
            long totalTokens,
            long costMicros,
            String currency) {}

    public record ModelUsageRow(
            String modelId,
            long inputTokens,
            long outputTokens,
            long totalTokens,
            long costMicros,
            int generationCount) {}

    public record ProviderUsageRow(String provider, long totalTokens, long costMicros) {}

    public record TopUserRow(String userId, long totalTokens, long costMicros, int generationCount) {}

    public record TimeSeriesRow(LocalDate date, long totalTokens, long costMicros) {}

    public record RecentRunRow(
            String runId,
            Long courseId,
            String generationType,
            String modelId,
            String status,
            Instant createdAt,
            long totalTokens,
            long costMicros) {}

    public LedgerTotals sumLedger(AiUsageFilter filter) {
        String sql = """
                SELECT
                    COALESCE(SUM(l.input_tokens), 0),
                    COALESCE(SUM(l.output_tokens), 0),
                    COALESCE(SUM(l.cached_tokens), 0),
                    COALESCE(SUM(l.reasoning_tokens), 0),
                    COALESCE(SUM(l.total_tokens), 0),
                    COALESCE(SUM(l.cost_micros), 0),
                    MAX(l.currency)
                FROM token_usage_ledger l
                JOIN generation_runs r ON r.id = l.generation_run_id
                WHERE l.created_at >= :from AND l.created_at < :toExclusive
                """ + runFilters(filter, "r", "l");

        Object[] row = (Object[]) bindRunFilters(entityManager.createNativeQuery(sql), filter)
                .setParameter("from", filter.from())
                .setParameter("toExclusive", filter.toExclusive())
                .getSingleResult();
        return new LedgerTotals(
                toLong(row[0]),
                toLong(row[1]),
                toLong(row[2]),
                toLong(row[3]),
                toLong(row[4]),
                toLong(row[5]),
                row[6] != null ? row[6].toString() : "USD");
    }

    public int countRuns(AiUsageFilter filter) {
        String sql = """
                SELECT COUNT(*)
                FROM generation_runs r
                WHERE r.created_at >= :from AND r.created_at < :toExclusive
                """ + runFilters(filter, "r", null);
        Number count = (Number) bindRunFilters(entityManager.createNativeQuery(sql), filter)
                .setParameter("from", filter.from())
                .setParameter("toExclusive", filter.toExclusive())
                .getSingleResult();
        return count != null ? count.intValue() : 0;
    }

    public int countFailedRuns(AiUsageFilter filter) {
        String sql = """
                SELECT COUNT(*)
                FROM generation_runs r
                WHERE r.created_at >= :from AND r.created_at < :toExclusive
                  AND r.status = 'FAILED'
                """ + runFilters(filter, "r", null);
        Number count = (Number) bindRunFilters(entityManager.createNativeQuery(sql), filter)
                .setParameter("from", filter.from())
                .setParameter("toExclusive", filter.toExclusive())
                .getSingleResult();
        return count != null ? count.intValue() : 0;
    }

    public int countUniqueTeachers(AiUsageFilter filter) {
        String sql = """
                SELECT COUNT(DISTINCT r.teacher_id)
                FROM generation_runs r
                WHERE r.created_at >= :from AND r.created_at < :toExclusive
                """ + runFilters(filter, "r", null);
        Number count = (Number) bindRunFilters(entityManager.createNativeQuery(sql), filter)
                .setParameter("from", filter.from())
                .setParameter("toExclusive", filter.toExclusive())
                .getSingleResult();
        return count != null ? count.intValue() : 0;
    }

    @SuppressWarnings("unchecked")
    public List<ModelUsageRow> usageByModel(AiUsageFilter filter) {
        String sql = """
                SELECT
                    l.model_id,
                    COALESCE(SUM(l.input_tokens), 0),
                    COALESCE(SUM(l.output_tokens), 0),
                    COALESCE(SUM(l.total_tokens), 0),
                    COALESCE(SUM(l.cost_micros), 0),
                    COUNT(DISTINCT r.id)
                FROM token_usage_ledger l
                JOIN generation_runs r ON r.id = l.generation_run_id
                WHERE l.created_at >= :from AND l.created_at < :toExclusive
                """ + runFilters(filter, "r", "l") + """
                 GROUP BY l.model_id
                 ORDER BY COALESCE(SUM(l.total_tokens), 0) DESC
                """;
        List<Object[]> rows = bindRunFilters(entityManager.createNativeQuery(sql), filter)
                .setParameter("from", filter.from())
                .setParameter("toExclusive", filter.toExclusive())
                .getResultList();
        List<ModelUsageRow> result = new ArrayList<>();
        for (Object[] row : rows) {
            result.add(new ModelUsageRow(
                    row[0].toString(),
                    toLong(row[1]),
                    toLong(row[2]),
                    toLong(row[3]),
                    toLong(row[4]),
                    toInt(row[5])));
        }
        return result;
    }

    @SuppressWarnings("unchecked")
    public List<ProviderUsageRow> usageByProvider(AiUsageFilter filter) {
        String sql = """
                SELECT
                    l.provider,
                    COALESCE(SUM(l.total_tokens), 0),
                    COALESCE(SUM(l.cost_micros), 0)
                FROM token_usage_ledger l
                JOIN generation_runs r ON r.id = l.generation_run_id
                WHERE l.created_at >= :from AND l.created_at < :toExclusive
                """ + runFilters(filter, "r", "l") + """
                 GROUP BY l.provider
                 ORDER BY COALESCE(SUM(l.total_tokens), 0) DESC
                """;
        List<Object[]> rows = bindRunFilters(entityManager.createNativeQuery(sql), filter)
                .setParameter("from", filter.from())
                .setParameter("toExclusive", filter.toExclusive())
                .getResultList();
        List<ProviderUsageRow> result = new ArrayList<>();
        for (Object[] row : rows) {
            result.add(new ProviderUsageRow(row[0].toString(), toLong(row[1]), toLong(row[2])));
        }
        return result;
    }

    @SuppressWarnings("unchecked")
    public List<TopUserRow> topUsers(AiUsageFilter filter, int limit) {
        String sql = """
                SELECT
                    r.teacher_id,
                    COALESCE(SUM(l.total_tokens), 0),
                    COALESCE(SUM(l.cost_micros), 0),
                    COUNT(DISTINCT r.id)
                FROM generation_runs r
                LEFT JOIN token_usage_ledger l ON l.generation_run_id = r.id
                    AND l.created_at >= :from AND l.created_at < :toExclusive
                WHERE r.created_at >= :from AND r.created_at < :toExclusive
                """ + runFilters(filter, "r", null) + """
                 GROUP BY r.teacher_id
                 ORDER BY COALESCE(SUM(l.total_tokens), 0) DESC
                 LIMIT :limit
                """;
        List<Object[]> rows = bindRunFilters(entityManager.createNativeQuery(sql), filter)
                .setParameter("from", filter.from())
                .setParameter("toExclusive", filter.toExclusive())
                .setParameter("limit", limit)
                .getResultList();
        List<TopUserRow> result = new ArrayList<>();
        for (Object[] row : rows) {
            result.add(new TopUserRow(row[0].toString(), toLong(row[1]), toLong(row[2]), toInt(row[3])));
        }
        return result;
    }

    @SuppressWarnings("unchecked")
    public List<TimeSeriesRow> dailyTimeSeriesFromAggregates(AiUsageFilter filter) {
        LocalDate fromDate = filter.from().atZone(java.time.ZoneOffset.UTC).toLocalDate();
        LocalDate toDate = filter.toExclusive().atZone(java.time.ZoneOffset.UTC).toLocalDate().minusDays(1);
        String sql = """
                SELECT
                    a.period_start,
                    COALESCE(SUM(a.total_tokens), 0),
                    COALESCE(SUM(a.cost_micros), 0)
                FROM ai_usage_aggregates a
                WHERE a.period_type = 'daily'
                  AND a.period_start >= :fromDate
                  AND a.period_start <= :toDate
                """ + aggregateTeacherFilter(filter) + """
                 GROUP BY a.period_start
                 ORDER BY a.period_start
                """;
        Query query = entityManager.createNativeQuery(sql)
                .setParameter("fromDate", fromDate)
                .setParameter("toDate", toDate);
        if (filter.teacherId() != null && !filter.teacherId().isBlank()) {
            query.setParameter("teacherId", filter.teacherId());
        }
        if (filter.modelId() != null && !filter.modelId().isBlank()) {
            query.setParameter("modelId", filter.modelId());
        }
        List<Object[]> rows = query.getResultList();
        List<TimeSeriesRow> result = new ArrayList<>();
        for (Object[] row : rows) {
            LocalDate date = row[0] instanceof java.sql.Date sqlDate
                    ? sqlDate.toLocalDate()
                    : LocalDate.parse(row[0].toString());
            result.add(new TimeSeriesRow(date, toLong(row[1]), toLong(row[2])));
        }
        return result;
    }

    @SuppressWarnings("unchecked")
    public List<RecentRunRow> recentRuns(AiUsageFilter filter, int limit) {
        String sql = """
                SELECT
                    r.id,
                    r.course_id,
                    r.generation_type,
                    COALESCE(r.actual_model_id, r.requested_model_id),
                    r.status,
                    r.created_at,
                    COALESCE(SUM(l.total_tokens), 0),
                    COALESCE(SUM(l.cost_micros), 0)
                FROM generation_runs r
                LEFT JOIN token_usage_ledger l ON l.generation_run_id = r.id
                WHERE r.created_at >= :from AND r.created_at < :toExclusive
                """ + runFilters(filter, "r", null) + """
                 GROUP BY r.id, r.course_id, r.generation_type, r.actual_model_id,
                          r.requested_model_id, r.status, r.created_at
                 ORDER BY r.created_at DESC
                 LIMIT :limit
                """;
        List<Object[]> rows = bindRunFilters(entityManager.createNativeQuery(sql), filter)
                .setParameter("from", filter.from())
                .setParameter("toExclusive", filter.toExclusive())
                .setParameter("limit", limit)
                .getResultList();
        List<RecentRunRow> result = new ArrayList<>();
        for (Object[] row : rows) {
            Instant createdAt = row[5] instanceof java.sql.Timestamp ts
                    ? ts.toInstant()
                    : Instant.parse(row[5].toString());
            result.add(new RecentRunRow(
                    row[0].toString(),
                    row[1] != null ? ((Number) row[1]).longValue() : null,
                    row[2].toString(),
                    row[3].toString(),
                    row[4].toString(),
                    createdAt,
                    toLong(row[6]),
                    toLong(row[7])));
        }
        return result;
    }

    private static String runFilters(AiUsageFilter filter, String runAlias, String ledgerAlias) {
        StringBuilder sb = new StringBuilder();
        if (filter.teacherId() != null && !filter.teacherId().isBlank()) {
            sb.append(" AND ").append(runAlias).append(".teacher_id = :teacherId");
        }
        if (filter.courseId() != null) {
            sb.append(" AND ").append(runAlias).append(".course_id = :courseId");
        }
        if (filter.generationType() != null && !filter.generationType().isBlank()) {
            sb.append(" AND ").append(runAlias).append(".generation_type = :generationType");
        }
        if (filter.status() != null && !filter.status().isBlank()) {
            sb.append(" AND ").append(runAlias).append(".status = :status");
        }
        if (filter.provider() != null && !filter.provider().isBlank()) {
            sb.append(" AND ").append(runAlias).append(".actual_provider = :provider");
        }
        if (filter.modelId() != null && !filter.modelId().isBlank()) {
            if (ledgerAlias != null) {
                sb.append(" AND ").append(ledgerAlias).append(".model_id = :modelId");
            } else {
                sb.append(" AND COALESCE(")
                        .append(runAlias)
                        .append(".actual_model_id, ")
                        .append(runAlias)
                        .append(".requested_model_id) = :modelId");
            }
        }
        return sb.toString();
    }

    private static String aggregateTeacherFilter(AiUsageFilter filter) {
        StringBuilder sb = new StringBuilder();
        if (filter.teacherId() != null && !filter.teacherId().isBlank()) {
            sb.append(" AND a.teacher_id = :teacherId");
        }
        if (filter.modelId() != null && !filter.modelId().isBlank()) {
            sb.append(" AND a.model_id = :modelId");
        }
        return sb.toString();
    }

    private static Query bindRunFilters(Query query, AiUsageFilter filter) {
        if (filter.teacherId() != null && !filter.teacherId().isBlank()) {
            query.setParameter("teacherId", filter.teacherId());
        }
        if (filter.courseId() != null) {
            query.setParameter("courseId", filter.courseId());
        }
        if (filter.generationType() != null && !filter.generationType().isBlank()) {
            query.setParameter("generationType", filter.generationType().trim().toUpperCase());
        }
        if (filter.status() != null && !filter.status().isBlank()) {
            query.setParameter("status", filter.status().trim().toUpperCase());
        }
        if (filter.provider() != null && !filter.provider().isBlank()) {
            query.setParameter("provider", filter.provider().trim().toLowerCase());
        }
        if (filter.modelId() != null && !filter.modelId().isBlank()) {
            query.setParameter("modelId", filter.modelId().trim());
        }
        return query;
    }

    private static long toLong(Object value) {
        if (value == null) {
            return 0L;
        }
        if (value instanceof Number number) {
            return number.longValue();
        }
        return Long.parseLong(value.toString());
    }

    private static int toInt(Object value) {
        if (value == null) {
            return 0;
        }
        if (value instanceof Number number) {
            return number.intValue();
        }
        return Integer.parseInt(value.toString());
    }
}
