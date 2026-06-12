package com.microservices.courseservice.dto.ai;

import lombok.Data;

@Data
public class UpdateTeacherAiLimitRequest {
    private Boolean unlimitedAccess;
    private Long monthlyTokenLimit;
    private Long dailyTokenLimit;
    private String note;
}
