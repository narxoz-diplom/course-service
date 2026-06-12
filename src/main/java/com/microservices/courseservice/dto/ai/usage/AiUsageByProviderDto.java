package com.microservices.courseservice.dto.ai.usage;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class AiUsageByProviderDto {
    private String provider;
    private Long totalTokens;
    private Long costMicros;
}
