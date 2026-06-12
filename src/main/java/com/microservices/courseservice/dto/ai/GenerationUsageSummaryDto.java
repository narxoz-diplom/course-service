package com.microservices.courseservice.dto.ai;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class GenerationUsageSummaryDto {
    private String generationRunId;
    private String modelId;
    private Integer inputTokens;
    private Integer outputTokens;
    private Integer totalTokens;
    private Long costMicros;
    private String currency;
    private String usageSource;
}
