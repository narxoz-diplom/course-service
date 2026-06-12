package com.microservices.courseservice.dto;

import com.microservices.courseservice.dto.ai.GenerationUsageSummaryDto;
import com.microservices.courseservice.model.Lesson;
import lombok.Builder;
import lombok.Data;

import java.util.List;

@Data
@Builder
public class GenerateLessonsResultDto {
    private List<Lesson> lessons;
    private GenerationUsageSummaryDto usageSummary;
}
