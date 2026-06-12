package com.microservices.courseservice.service;

import com.microservices.courseservice.config.AiQuotaProperties;
import com.microservices.courseservice.dto.ai.UpdateTeacherAiLimitRequest;
import com.microservices.courseservice.exception.AiModelException;
import com.microservices.courseservice.model.ai.TeacherAiLimit;
import com.microservices.courseservice.repository.AiUsageAggregateRepository;
import com.microservices.courseservice.repository.TeacherAiLimitRepository;
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
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class TeacherAiLimitServiceTest {

    @Mock private TeacherAiLimitRepository teacherAiLimitRepository;
    @Mock private AiUsageAggregateRepository aggregateRepository;

    private TeacherAiLimitService service;

    @BeforeEach
    void setUp() {
        AiQuotaProperties props = new AiQuotaProperties();
        props.setTeacherDefaultMonthlyTokens(1_000_000L);
        props.setTeacherDefaultDailyTokens(150_000L);
        service = new TeacherAiLimitService(teacherAiLimitRepository, aggregateRepository, props);
    }

    @Test
    void teacherUsesPlatformDefaultWhenNoOverride() {
        LocalDate monthStart = LocalDate.now(ZoneOffset.UTC).withDayOfMonth(1);
        when(teacherAiLimitRepository.findById("teacher-1")).thenReturn(Optional.empty());
        when(aggregateRepository.sumTotalTokensByTeacher(
                eq("teacher-1"), eq("monthly"), eq(monthStart))).thenReturn(100_000L);
        when(aggregateRepository.sumTotalTokensByTeacher(
                eq("teacher-1"), eq("daily"), eq(LocalDate.now(ZoneOffset.UTC)))).thenReturn(10_000L);

        TeacherAiLimitService.UserQuotaEvaluation evaluation = service.evaluate("teacher-1", false);

        assertThat(evaluation.unlimited()).isFalse();
        assertThat(evaluation.customOverride()).isFalse();
        assertThat(evaluation.monthlyLimit()).isEqualTo(1_000_000L);
        assertThat(evaluation.monthlyRemaining()).isEqualTo(900_000L);
        assertThat(evaluation.dailyLimit()).isEqualTo(150_000L);
        assertThat(evaluation.blocked()).isFalse();
    }

    @Test
    void generationBlockedWhenMonthlyUserLimitExceeded() {
        LocalDate monthStart = LocalDate.now(ZoneOffset.UTC).withDayOfMonth(1);
        when(teacherAiLimitRepository.findById("teacher-1")).thenReturn(Optional.empty());
        when(aggregateRepository.sumTotalTokensByTeacher(
                eq("teacher-1"), eq("monthly"), eq(monthStart))).thenReturn(1_000_000L);
        when(aggregateRepository.sumTotalTokensByTeacher(
                eq("teacher-1"), eq("daily"), any())).thenReturn(0L);

        assertThatThrownBy(() -> service.assertWithinUserQuota("teacher-1", false))
                .isInstanceOf(AiModelException.class)
                .satisfies(ex -> assertThat(((AiModelException) ex).getCode())
                        .isEqualTo(AiModelException.Code.QUOTA_EXCEEDED));
    }

    @Test
    void adminIsUnlimited() {
        TeacherAiLimitService.UserQuotaEvaluation evaluation = service.evaluate("admin-1", true);
        assertThat(evaluation.unlimited()).isTrue();
        assertThat(evaluation.blocked()).isFalse();
    }

    @Test
    void adminCanGrantUnlimitedOverride() {
        when(teacherAiLimitRepository.findById("teacher-1"))
                .thenReturn(Optional.of(TeacherAiLimit.builder()
                        .teacherId("teacher-1")
                        .unlimitedAccess(true)
                        .build()));
        when(aggregateRepository.sumTotalTokensByTeacher(any(), any(), any())).thenReturn(5_000_000L);

        TeacherAiLimitService.UserQuotaEvaluation evaluation = service.evaluate("teacher-1", false);

        assertThat(evaluation.unlimited()).isTrue();
        assertThat(evaluation.customOverride()).isTrue();
        assertThat(evaluation.blocked()).isFalse();
    }

    @Test
    void adminUpdatePersistsCustomLimit() {
        TeacherAiLimit saved = TeacherAiLimit.builder()
                .teacherId("teacher-1")
                .unlimitedAccess(false)
                .monthlyTokenLimit(3_000_000L)
                .dailyTokenLimit(500_000L)
                .note("VIP teacher")
                .build();
        when(teacherAiLimitRepository.findById("teacher-1"))
                .thenReturn(Optional.empty())
                .thenReturn(Optional.of(saved));
        when(teacherAiLimitRepository.save(any())).thenReturn(saved);
        when(aggregateRepository.sumTotalTokensByTeacher(any(), any(), any())).thenReturn(0L);

        UpdateTeacherAiLimitRequest request = new UpdateTeacherAiLimitRequest();
        request.setUnlimitedAccess(false);
        request.setMonthlyTokenLimit(3_000_000L);
        request.setDailyTokenLimit(500_000L);
        request.setNote("VIP teacher");

        var status = service.adminUpdate("teacher-1", "admin-1", request);

        assertThat(status.getMonthlyLimit()).isEqualTo(3_000_000L);
        assertThat(status.getDailyLimit()).isEqualTo(500_000L);
        assertThat(status.isCustomOverride()).isTrue();
        verify(teacherAiLimitRepository).save(any(TeacherAiLimit.class));
    }
}
