package com.microservices.courseservice.dto;

import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@Builder
public class LessonProgressItemDto {
    private Long lessonId;
    private boolean completed;
    private LocalDateTime completedAt;
}
