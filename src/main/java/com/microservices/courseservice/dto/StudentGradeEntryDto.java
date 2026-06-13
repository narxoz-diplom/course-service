package com.microservices.courseservice.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class StudentGradeEntryDto {

    private Long courseId;
    private String courseTitle;
    private Long lessonId;
    private String lessonTitle;
    private String moduleId;
    private String moduleTitle;
    private Integer grade;
    private String feedback;
    private String gradedAt;
}
