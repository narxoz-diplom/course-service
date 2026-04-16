package com.microservices.courseservice.dto;

import lombok.Data;

import java.util.List;
import java.util.Map;

@Data
public class RagLessonsResponse {
    private List<RagLessonDto> lessons;
    private Map<String, List<RagLessonDto>> translations;
}
