package com.microservices.courseservice.dto;

import lombok.Builder;
import lombok.Data;

import java.util.List;

@Data
@Builder
public class CourseProgressDto {
    private Long courseId;
    private int totalLessons;
    private int completedLessons;
    private int progressPercent;
    private List<LessonProgressItemDto> lessons;
}
