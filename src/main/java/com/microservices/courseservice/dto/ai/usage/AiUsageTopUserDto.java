package com.microservices.courseservice.dto.ai.usage;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class AiUsageTopUserDto {
    private String userId;
    private Long totalTokens;
    private Long costMicros;
    private Integer generationCount;
}
