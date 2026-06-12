package com.microservices.courseservice.dto;

import com.microservices.courseservice.dto.ai.GenerationUsageSummaryDto;
import com.microservices.courseservice.model.Test;
import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class GenerateTestResultDto {
    private Test test;
    private GenerationUsageSummaryDto usageSummary;
}
