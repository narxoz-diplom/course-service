package com.microservices.courseservice.dto;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class LessonGenerationJobDto {
    private String jobId;
    private String status;
    private String generationRunId;
    private String createdLessonIds;
    private Integer totalLessons;
    private Integer completedLessons;
    private String currentLessonTitle;
    private String errorMessage;
    private com.microservices.courseservice.dto.ai.GenerationUsageSummaryDto usageSummary;
}
