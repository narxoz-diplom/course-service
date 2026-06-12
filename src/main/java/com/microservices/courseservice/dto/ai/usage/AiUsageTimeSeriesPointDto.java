package com.microservices.courseservice.dto.ai.usage;

import lombok.Builder;
import lombok.Data;

import java.time.LocalDate;

@Data
@Builder
public class AiUsageTimeSeriesPointDto {
    private LocalDate date;
    private Long totalTokens;
    private Long costMicros;
}
