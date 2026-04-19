package com.microservices.courseservice.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Slim row for teacher/admin test results table (no questions payload).
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TestAttemptTeacherRowDto {

    private Long attemptId;
    private String studentId;
    private Long testId;
    private String testTitle;
    private String testTitleKz;
    private String testTitleEn;
    private Integer score;
    private Integer maxScore;
    /** ISO-8601 string */
    private String completedAt;
    private Boolean suspiciousFlag;
}
