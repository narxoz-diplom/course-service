package com.microservices.courseservice.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class VectorCleanupMessage {

    public static final String EVENT_COURSE_DELETED = "COURSE_DELETED";
    public static final String EVENT_LESSON_DELETED = "LESSON_DELETED";

    private String eventType;
    private String courseId;
    private String lessonId;
    private String collectionName;
}
