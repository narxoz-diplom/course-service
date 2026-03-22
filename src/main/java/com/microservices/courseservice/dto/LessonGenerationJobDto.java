package com.microservices.courseservice.dto;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class LessonGenerationJobDto {
    private String jobId;
    private String status;
    private String createdLessonIds;
    private String errorMessage;
}
