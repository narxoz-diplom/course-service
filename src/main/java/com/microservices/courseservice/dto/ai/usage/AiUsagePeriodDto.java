package com.microservices.courseservice.dto.ai.usage;

import lombok.Builder;
import lombok.Data;

import java.time.LocalDate;

@Data
@Builder
public class AiUsagePeriodDto {
    private LocalDate from;
    private LocalDate to;
}
