package com.microservices.courseservice.dto;

import lombok.Data;

import java.util.List;

@Data
public class GenerateFromOutlineRequest {
    private List<Long> fileIds;
    private LessonGenerationParamsDto params;
    /** Teacher-approved outline (edited order/titles allowed). */
    private List<LessonOutlineItemDto> outline;
}
