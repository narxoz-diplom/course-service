package com.microservices.courseservice.dto.ai;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class AiModelQuotaDto {
    private Long limitTokens;
    private Long usedTokens;
    private Long remainingTokens;
    private String period;
    private boolean blocked;
    private AiModelQuotaDto daily;
}
