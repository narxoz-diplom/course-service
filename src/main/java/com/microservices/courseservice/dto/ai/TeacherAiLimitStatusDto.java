package com.microservices.courseservice.dto.ai;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class TeacherAiLimitStatusDto {
    private String teacherId;
    private boolean unlimited;
    private boolean customOverride;
    private Long monthlyLimit;
    private Long monthlyUsed;
    private Long monthlyRemaining;
    private Long dailyLimit;
    private Long dailyUsed;
    private Long dailyRemaining;
    private boolean blocked;
    private String blockReason;
    private String note;
}
