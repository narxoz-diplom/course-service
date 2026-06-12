package com.microservices.courseservice.dto.ai;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class AiModelPriceHintDto {
    private Long inputPer1MTokensMicros;
    private Long outputPer1MTokensMicros;
    private String currency;
}
