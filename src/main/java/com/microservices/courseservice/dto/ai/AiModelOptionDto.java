package com.microservices.courseservice.dto.ai;

import lombok.Builder;
import lombok.Data;

import java.util.List;

@Data
@Builder
public class AiModelOptionDto {
    private String id;
    private String displayName;
    private String provider;
    private String tier;
    private String description;
    private Long contextWindowTokens;
    private List<String> capabilities;
    private boolean isDefault;
    private boolean enabled;
    private String unavailableReason;
    private AiModelPriceHintDto priceHint;
    private AiModelQuotaDto quota;
}
