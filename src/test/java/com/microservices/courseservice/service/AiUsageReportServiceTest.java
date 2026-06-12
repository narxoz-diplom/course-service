package com.microservices.courseservice.service;

import com.microservices.courseservice.dto.ai.usage.TeacherAiUsageReportDto;
import com.microservices.courseservice.repository.AiModelPolicyRepository;
import com.microservices.courseservice.repository.AiModelRepository;
import com.microservices.courseservice.repository.AiUsageReportRepository;
import com.microservices.courseservice.repository.AiUsageReportRepository.AiUsageFilter;
import com.microservices.courseservice.repository.AiUsageReportRepository.LedgerTotals;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.oauth2.jwt.Jwt;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AiUsageReportServiceTest {

    @Mock private AiUsageReportRepository reportRepository;
    @Mock private AiModelRepository aiModelRepository;
    @Mock private AiModelPolicyRepository aiModelPolicyRepository;
    @Mock private AiQuotaService aiQuotaService;

    private AiUsageReportService service;

    @BeforeEach
    void setUp() {
        service = new AiUsageReportService(
                reportRepository, aiModelRepository, aiModelPolicyRepository, aiQuotaService);
    }

    @Test
    void teacherUsageReturnsOwnReport() {
        Jwt teacherJwt = Jwt.withTokenValue("token")
                .header("alg", "none")
                .subject("teacher-1")
                .claim("realm_access", Map.of("roles", List.of("teacher")))
                .build();

        when(reportRepository.sumLedger(any())).thenReturn(
                new LedgerTotals(1000, 500, 0, 0, 1500, 450L, "USD"));
        when(reportRepository.countRuns(any())).thenReturn(3);
        when(reportRepository.countFailedRuns(any())).thenReturn(1);
        when(reportRepository.usageByModel(any())).thenReturn(List.of());
        when(reportRepository.dailyTimeSeriesFromAggregates(any())).thenReturn(List.of());
        when(reportRepository.recentRuns(any(), anyInt())).thenReturn(List.of());
        when(aiModelRepository.findAll()).thenReturn(List.of());
        when(aiModelPolicyRepository.findByAllowedRoleAndCapability(any(), any())).thenReturn(List.of());

        TeacherAiUsageReportDto report = service.teacherUsage(teacherJwt, LocalDate.parse("2026-06-01"), LocalDate.parse("2026-06-30"));

        assertThat(report.getSummary().getTotalTokens()).isEqualTo(1500L);
        assertThat(report.getSummary().getGenerationCount()).isEqualTo(3);
        assertThat(report.getSummary().getFailedCount()).isEqualTo(1);
        assertThat(report.getPeriod().getFrom()).isEqualTo(LocalDate.parse("2026-06-01"));
    }

    @Test
    void clientCannotAccessTeacherUsage() {
        Jwt clientJwt = Jwt.withTokenValue("token")
                .header("alg", "none")
                .subject("client-1")
                .claim("realm_access", Map.of("roles", List.of("client")))
                .build();

        assertThatThrownBy(() -> service.teacherUsage(clientJwt, null, null))
                .isInstanceOf(AccessDeniedException.class);
    }

    @Test
    void teacherCannotAccessAdminUsage() {
        Jwt teacherJwt = Jwt.withTokenValue("token")
                .header("alg", "none")
                .subject("teacher-1")
                .claim("realm_access", Map.of("roles", List.of("teacher")))
                .build();

        assertThatThrownBy(() -> service.adminUsage(teacherJwt, null, null, null, null, null, null, null, null))
                .isInstanceOf(AccessDeniedException.class);
    }
}
