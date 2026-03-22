package com.microservices.courseservice.dto;

import lombok.Data;

@Data
public class LessonGenerationParamsDto {
    private String teacherBrief;
    /** school | bachelor | pro */
    private String targetAudience;
    private Integer minLessons;
    private Integer maxLessons;
    /** shallow | medium | deep */
    private String depth;
    /** full_collection | semantic */
    private String retrievalMode;
    private String retrievalQuery;
}
