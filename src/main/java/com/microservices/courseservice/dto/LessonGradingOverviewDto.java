package com.microservices.courseservice.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class LessonGradingOverviewDto {

    private Long id;
    private String title;
    private String titleKz;
    private String titleEn;
    private String description;
    private String descriptionKz;
    private String descriptionEn;
    private String content;
    private String contentKz;
    private String contentEn;
    private Integer orderNumber;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    private Integer gradedCount;
    private Integer totalStudents;
    private String gradeStatus;
}
