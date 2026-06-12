package com.microservices.courseservice.dto.ai.usage;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class AiQuotaUtilizationDto {
    private String modelId;
    private String displayName;
    private String tier;
    private String userId;
    private Long monthlyLimitTokens;
    private Long monthlyUsedTokens;
    private Long monthlyRemainingTokens;
    private Long dailyLimitTokens;
    private Long dailyUsedTokens;
    private Long dailyRemainingTokens;
    private boolean blocked;
}
