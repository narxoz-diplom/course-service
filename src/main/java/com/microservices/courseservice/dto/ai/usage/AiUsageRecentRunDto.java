package com.microservices.courseservice.dto.ai.usage;

import lombok.Builder;
import lombok.Data;

import java.time.Instant;

@Data
@Builder
public class AiUsageRecentRunDto {
    private String generationRunId;
    private Long courseId;
    private String generationType;
    private String modelId;
    private String status;
    private Long totalTokens;
    private Long costMicros;
    private Instant createdAt;
}
