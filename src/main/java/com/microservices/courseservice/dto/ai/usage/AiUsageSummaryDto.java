package com.microservices.courseservice.dto.ai.usage;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class AiUsageSummaryDto {
    private Long totalTokens;
    private Long inputTokens;
    private Long outputTokens;
    private Long cachedTokens;
    private Long reasoningTokens;
    private Long costMicros;
    private String currency;
    private Integer generationCount;
    private Integer failedCount;
    private Integer uniqueTeachers;
}
