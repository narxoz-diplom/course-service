package com.microservices.courseservice.dto.ai.usage;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class AiUsageByModelDto {
    private String modelId;
    private String displayName;
    private Long totalTokens;
    private Long inputTokens;
    private Long outputTokens;
    private Long costMicros;
    private Integer generationCount;
}
