package com.microservices.courseservice.dto;

import lombok.Data;

import java.util.List;

@Data
public class RagLessonsResponse {
    private List<RagLessonDto> lessons;
}
