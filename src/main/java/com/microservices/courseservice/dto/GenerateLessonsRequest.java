package com.microservices.courseservice.dto;

import lombok.Data;

import java.util.List;

@Data
public class GenerateLessonsRequest {
    private List<Long> fileIds;
    private String prompt;
    private Integer topK;
    private LessonGenerationParamsDto params;
}
