package com.microservices.courseservice.dto;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class MyTestAttemptDto {
    private Long attemptId;
    private Long testId;
    private Integer score;
    private Integer maxScore;
    private String completedAt;
    private Boolean suspiciousFlag;
}

