package com.microservices.courseservice.repository;

import com.microservices.courseservice.model.ai.AiUsageAggregate;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;
import java.util.Optional;

public interface AiUsageAggregateRepository extends JpaRepository<AiUsageAggregate, Long> {

    Optional<AiUsageAggregate> findByTeacherIdAndModelIdAndPeriodTypeAndPeriodStart(
            String teacherId, String modelId, String periodType, LocalDate periodStart);

    @Query("""
            SELECT COALESCE(SUM(a.totalTokens), 0) FROM AiUsageAggregate a
            WHERE a.teacherId = :teacherId
              AND a.periodType = :periodType
              AND a.periodStart = :periodStart
            """)
    long sumTotalTokensByTeacher(
            @Param("teacherId") String teacherId,
            @Param("periodType") String periodType,
            @Param("periodStart") LocalDate periodStart);
}
