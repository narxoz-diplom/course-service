package com.microservices.courseservice.dto;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class SearchResultDto {
    private String type;
    private Long courseId;
    private Long lessonId;
    private Long testId;
    private String title;
    private String titleKz;
    private String titleEn;
    private String description;
    private String descriptionKz;
    private String descriptionEn;
    private String courseTitle;
    private String courseTitleKz;
    private String courseTitleEn;
}
